package ru.egor.meters

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import java.math.BigDecimal
import java.time.YearMonth
import java.util.UUID

data class Reading(
    val id:String=UUID.randomUUID().toString(),
    val value:Double,
    val timestamp:Long=System.currentTimeMillis(),
    val photoUri:String?=null,
    val note:String="",
    val valueText:String?=null,
    val rollover:Boolean=false,
    val zoneValues:Map<String,String> = valueText?.let{mapOf("TOTAL" to it)}?:emptyMap(),
    val billingPeriod:String?=null
)

data class Meter(
    val id:String=UUID.randomUUID().toString(),
    val name:String,
    val unit:String,
    val kind:String="other",
    val serial:String="",
    val readings:List<Reading> = emptyList(),
    val integerDigits:Int?=null,
    val fractionDigits:Int?=null,
    val previousMeterId:String?=null,
    val installedAt:Long?=null,
    val verificationUntil:Long?=null,
    val status:String?=null,
    val account:String="",
    val recipient:String="",
    val tariffZones:List<String> = MeterZones.SINGLE,
    val tariffSchedule:List<TariffScheduleEntry> = emptyList(),
    val location:String="",
    val meteringPointId:String?=null
)

data class Address(val id:String=UUID.randomUUID().toString(),val name:String,val meters:List<Meter> = emptyList(),val account:String="",val recipient:String="")

data class SubmissionItem(val meteringPointId:String,val meterId:String,val zone:String="TOTAL",val valueText:String)
data class Submission(val id:String=UUID.randomUUID().toString(),val addressId:String,val billingPeriod:String,val submittedAt:Long=System.currentTimeMillis(),val recipient:String="",val snapshotText:String="",val items:List<SubmissionItem> = emptyList())

class MeterRepository(
 private val context:Context,
 private val db:MeterDatabase=MeterDatabase.get(context),
 private val migrateLegacy:Boolean=true
){
 private val dao=db.meterDao();private val legacyPrefs=context.getSharedPreferences("meters",Context.MODE_PRIVATE)
 private var lastLoadedSnapshot:List<Address>?=null
 init{if(migrateLegacy)migrateLegacyJsonIfNeeded()}

 fun load():List<Address> = loadCurrent().also { lastLoadedSnapshot=it }

 private fun loadCurrent():List<Address>{
  val addresses=dao.addresses()
  val points=dao.meteringPoints().associateBy{it.id}
  val meters=dao.meters().groupBy{it.addressId}
  val readings=dao.readings().groupBy{it.meterId}
  val values=dao.readingValues().groupBy{it.readingId}
  val schedules=dao.tariffSchedule().groupBy{it.meterId}
  return addresses.map{a->Address(a.id,a.name,meters[a.id].orEmpty().map{m->
   val point=m.meteringPointId?.let(points::get)
   Meter(
    id=m.id,
    name=point?.name?:m.name,
    unit=point?.unit?:m.unit,
    kind=point?.kind?:m.kind,
    serial=m.serial,
    readings=readings[m.id].orEmpty().mapNotNull{r->
     val zoneMap=values[r.id].orEmpty().associate{it.zone to it.valueText}
     val primary=zoneMap["TOTAL"]?:zoneMap["T1"]?:return@mapNotNull null
     Reading(r.id,primary.toDoubleOrNull()?:return@mapNotNull null,r.timestamp,r.photoUri,r.note,primary,r.source=="manual_rollover",zoneMap,r.billingPeriod)
    },
    integerDigits=m.integerDigits,
    fractionDigits=m.fractionDigits,
    previousMeterId=m.previousMeterId,
    installedAt=m.installedAt,
    verificationUntil=m.verificationUntil,
    status=m.status,
    account=point?.account?.ifBlank{m.account}?:m.account,
    recipient=point?.recipient?.ifBlank{m.recipient}?:m.recipient,
    tariffZones=MeterZones.normalize(m.tariffZones.split(',')),
    tariffSchedule=schedules[m.id].orEmpty().map{TariffScheduleEntry(it.zone,it.validFrom,it.priceText)},
    location=point?.location?.ifBlank{m.location}?:m.location,
    meteringPointId=m.meteringPointId
   )
  },a.account,a.recipient)}
 }

 fun submissions(addressId:String?=null):List<Submission>{
  val items=dao.submissionItems().groupBy{it.submissionId}
  return dao.submissions().filter{addressId==null||it.addressId==addressId}.map{s->
   Submission(s.id,s.addressId,s.billingPeriod,s.submittedAt,s.recipient,s.snapshotText,items[s.id].orEmpty().map{SubmissionItem(it.meteringPointId,it.meterId,it.zone,it.valueText)})
  }
 }

 fun recordSubmission(submission:Submission){
  require(runCatching{YearMonth.parse(submission.billingPeriod)}.isSuccess){"Некорректный отчётный период"}
  require(submission.items.isNotEmpty()){ "Передача не содержит показаний" }
  db.runInTransaction{
   dao.insertSubmissions(listOf(SubmissionEntity(submission.id,submission.addressId,submission.billingPeriod,submission.submittedAt,submission.recipient,submission.snapshotText)))
   dao.insertSubmissionItems(submission.items.map{SubmissionItemEntity(submission.id,it.meteringPointId,it.meterId,it.zone,it.valueText)})
  }
 }

 fun save(addresses:List<Address>){
  val old=dao.readings().mapNotNull{it.photoUri}.toSet()
  val baseline=lastLoadedSnapshot
  val current=loadCurrent()
  val merged=if(baseline==null)addresses else mergeUserChanges(baseline,addresses,current)
  replaceAll(merged,true,null)
  val fresh=loadCurrent()
  val currentPhotos=fresh.flatMap{it.meters}.flatMap{it.readings}.mapNotNull{it.photoUri}.toSet()
  (old-currentPhotos).forEach(::deleteOwnedPhoto)
  lastLoadedSnapshot=fresh
 }

 fun restoreValidated(addresses:List<Address>,submissions:List<Submission> = emptyList()){
  val validationName="restore-validation-${UUID.randomUUID()}.db"
  val validationDb=MeterDatabase.openTemporary(context,validationName)
  try{
   val validationRepo=MeterRepository(context,validationDb,false)
   validationRepo.replaceAll(addresses,false,submissions)
   validationDb.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use{cursor->
    require(!cursor.moveToFirst()){ "В резервной копии нарушены связи данных" }
   }
   validationDb.openHelper.writableDatabase.query("PRAGMA integrity_check").use{cursor->
    require(cursor.moveToFirst()&&cursor.getString(0)=="ok"){ "Временная база не прошла integrity_check" }
   }
  }finally{
   validationDb.close()
   context.deleteDatabase(validationName)
  }
  val old=dao.readings().mapNotNull{it.photoUri}.toSet()
  val restoredPhotos=addresses.flatMap{it.meters}.flatMap{it.readings}.mapNotNull{it.photoUri}.toSet()
  // From this point replaceAll is the commit point. Do not let post-commit refresh/cleanup turn
  // a successful DB replacement into a reported failure, because the caller would then delete
  // prepared photos that the committed DB already references.
  replaceAll(addresses,false,submissions)
  lastLoadedSnapshot=runCatching{loadCurrent()}.getOrDefault(addresses)
  (old-restoredPhotos).forEach(::deleteOwnedPhoto)
 }

 private fun mergeUserChanges(base:List<Address>,updated:List<Address>,current:List<Address>):List<Address>{
  val baseById=base.associateBy{it.id};val updatedById=updated.associateBy{it.id}
  val removedIds=baseById.keys-updatedById.keys
  val result=current.filterNot{it.id in removedIds}.toMutableList()
  updated.forEach{u->
   val b=baseById[u.id]
   if(b==null){
    if(result.none{it.id==u.id})result+=u
   }else{
    val index=result.indexOfFirst{it.id==u.id}
    if(index>=0)result[index]=mergeAddress(b,u,result[index])
   }
  }
  return result
 }

 private fun mergeAddress(base:Address,updated:Address,current:Address):Address{
  val baseMeters=base.meters.associateBy{it.id};val updatedMeters=updated.meters.associateBy{it.id}
  val removedIds=baseMeters.keys-updatedMeters.keys
  val meters=current.meters.filterNot{it.id in removedIds}.toMutableList()
  updated.meters.forEach{u->
   val b=baseMeters[u.id]
   if(b==null){
    if(meters.none{it.id==u.id})meters+=u
   }else{
    val index=meters.indexOfFirst{it.id==u.id}
    if(index>=0)meters[index]=mergeMeter(b,u,meters[index])
   }
  }
  return current.copy(
   name=pick(base.name,updated.name,current.name),
   meters=meters,
   account=pick(base.account,updated.account,current.account),
   recipient=pick(base.recipient,updated.recipient,current.recipient)
  )
 }

 private fun mergeMeter(base:Meter,updated:Meter,current:Meter):Meter{
  val baseReadings=base.readings.associateBy{it.id};val updatedReadings=updated.readings.associateBy{it.id}
  val removedIds=baseReadings.keys-updatedReadings.keys
  val readings=current.readings.filterNot{it.id in removedIds}.toMutableList()
  updated.readings.forEach{u->
   val b=baseReadings[u.id]
   if(b==null){
    if(readings.none{it.id==u.id})readings+=u
   }else if(u!=b){
    val index=readings.indexOfFirst{it.id==u.id}
    if(index>=0)readings[index]=mergeReading(b,u,readings[index])
   }
  }
  return current.copy(
   name=pick(base.name,updated.name,current.name),
   unit=pick(base.unit,updated.unit,current.unit),
   kind=pick(base.kind,updated.kind,current.kind),
   serial=pick(base.serial,updated.serial,current.serial),
   readings=readings,
   integerDigits=pick(base.integerDigits,updated.integerDigits,current.integerDigits),
   fractionDigits=pick(base.fractionDigits,updated.fractionDigits,current.fractionDigits),
   previousMeterId=pick(base.previousMeterId,updated.previousMeterId,current.previousMeterId),
   installedAt=pick(base.installedAt,updated.installedAt,current.installedAt),
   verificationUntil=pick(base.verificationUntil,updated.verificationUntil,current.verificationUntil),
   status=pick(base.status,updated.status,current.status),
   account=pick(base.account,updated.account,current.account),
   recipient=pick(base.recipient,updated.recipient,current.recipient),
   tariffZones=pick(base.tariffZones,updated.tariffZones,current.tariffZones),
   tariffSchedule=pick(base.tariffSchedule,updated.tariffSchedule,current.tariffSchedule),
   location=pick(base.location,updated.location,current.location),
   meteringPointId=pick(base.meteringPointId,updated.meteringPointId,current.meteringPointId)
  )
 }

 private fun mergeReading(base:Reading,updated:Reading,current:Reading):Reading{
  val explicitZones=updated.zoneValues!=base.zoneValues
  val legacySingleValueChanged=updated.valueText!=base.valueText &&
   (base.zoneValues.isEmpty() || base.zoneValues.keys==setOf("TOTAL"))
  val mergedZones=when{
   explicitZones->updated.zoneValues
   legacySingleValueChanged&&!updated.valueText.isNullOrBlank()->mapOf("TOTAL" to updated.valueText.orEmpty())
   else->current.zoneValues
  }
  val mergedPrimary=mergedZones["TOTAL"]?:mergedZones["T1"]?:current.valueText?:updated.valueText
  val mergedPeriod=when{
   updated.billingPeriod!=null&&updated.billingPeriod!=base.billingPeriod->updated.billingPeriod
   else->current.billingPeriod
  }
  return current.copy(
   value=mergedPrimary?.toDoubleOrNull()?:pick(base.value,updated.value,current.value),
   timestamp=pick(base.timestamp,updated.timestamp,current.timestamp),
   photoUri=pick(base.photoUri,updated.photoUri,current.photoUri),
   note=pick(base.note,updated.note,current.note),
   valueText=mergedPrimary,
   rollover=pick(base.rollover,updated.rollover,current.rollover),
   zoneValues=mergedZones,
   billingPeriod=mergedPeriod
  )
 }

 private fun <T> pick(base:T,updated:T,current:T):T=if(updated!=base)updated else current

 private fun replaceAll(addresses:List<Address>,preserve:Boolean,restoredSubmissions:List<Submission>?){
  val previous=if(preserve)dao.meters().associateBy{it.id}else emptyMap()
  val previousPoints=if(preserve)dao.meteringPoints().associateBy{it.id}else emptyMap()
  val incomingAddressIds=addresses.map{it.id}.toSet()
  val preservedSubmissions=if(preserve)dao.submissions().filter{it.addressId in incomingAddressIds}else emptyList()
  val preservedSubmissionIds=preservedSubmissions.map{it.id}.toSet()
  val preservedSubmissionItems=if(preserve)dao.submissionItems().filter{it.submissionId in preservedSubmissionIds}else emptyList()
  val ae=addresses.map{AddressEntity(it.id,it.name,it.account,it.recipient)}
  val pe=linkedMapOf<String,MeteringPointEntity>()
  val me=mutableListOf<MeterEntity>();val re=mutableListOf<ReadingEntity>();val ve=mutableListOf<ReadingValueEntity>();val te=mutableListOf<TariffScheduleEntity>()
  addresses.forEach{a->a.meters.forEach{m->
   val p=previous[m.id]
   val inheritedPointId=m.meteringPointId?:p?.meteringPointId
   val pointId=inheritedPointId?:m.previousMeterId?.let{previous[it]?.meteringPointId}?:m.id
   val oldPoint=previousPoints[pointId]
   val(di,df)=defaultDigits(m.kind)
   val zones=MeterZones.normalize(m.tariffZones)
   pe[pointId]=MeteringPointEntity(pointId,a.id,m.name,m.unit,m.kind,m.location.ifBlank{oldPoint?.location.orEmpty()},m.account.ifBlank{oldPoint?.account.orEmpty()},m.recipient.ifBlank{oldPoint?.recipient.orEmpty()},if((m.status?:p?.status)=="closed"&&addresses.flatMap{it.meters}.none{it.id!=m.id&&((it.meteringPointId?:previous[it.id]?.meteringPointId)==pointId)&&(it.status?:previous[it.id]?.status)!="closed"})"closed" else "active")
   me+=MeterEntity(m.id,a.id,m.name,m.unit,m.kind,m.serial,m.integerDigits?:p?.integerDigits?:di,m.fractionDigits?:p?.fractionDigits?:df,m.previousMeterId?:p?.previousMeterId,m.installedAt?:p?.installedAt,m.verificationUntil?:p?.verificationUntil,m.status?:p?.status?:"active",m.account.ifBlank{p?.account.orEmpty()},m.recipient.ifBlank{p?.recipient.orEmpty()},zones.joinToString(","),m.location.ifBlank{p?.location.orEmpty()},pointId)
   m.readings.forEach{r->
    r.billingPeriod?.let{require(runCatching{YearMonth.parse(it)}.isSuccess){"Некорректный отчётный период"}}
    re+=ReadingEntity(r.id,m.id,r.timestamp,r.photoUri,r.note,if(r.rollover)"manual_rollover" else "manual",r.billingPeriod)
    val vals=if(r.zoneValues.isNotEmpty())r.zoneValues else mapOf("TOTAL" to (r.valueText?:decimalText(r.value)))
    MeterZones.normalize(vals.keys).forEach{z->vals[z]?.let{ve+=ReadingValueEntity(r.id,z,it)}}
   }
   m.tariffSchedule.forEach{te+=TariffScheduleEntity(m.id,it.zone.uppercase(),it.validFrom,it.priceText)}
  }}

  val restoreSubmissionEntities=mutableListOf<SubmissionEntity>()
  val restoreSubmissionItems=mutableListOf<SubmissionItemEntity>()
  if(!preserve){
   val addressIds=ae.map{it.id}.toSet()
   restoredSubmissions.orEmpty().forEach{s->
    require(s.addressId in addressIds){"Передача относится к неизвестному адресу"}
    require(runCatching{YearMonth.parse(s.billingPeriod)}.isSuccess){"Некорректный отчётный период передачи"}
    require(s.items.isNotEmpty()){ "Передача не содержит показаний" }
    require(restoreSubmissionEntities.none{it.id==s.id}){"Повторяющийся идентификатор передачи"}
    restoreSubmissionEntities+=SubmissionEntity(s.id,s.addressId,s.billingPeriod,s.submittedAt,s.recipient,s.snapshotText)
    s.items.forEach{i->
     require(i.meteringPointId.isNotBlank()){ "Передача содержит пустую точку учёта" }
     require(i.meterId.isNotBlank()){ "Передача содержит пустой идентификатор прибора" }
     require(i.valueText.isNotBlank()){ "Пустое переданное значение" }
     require(MeterHistory.normalizeReadingText(i.valueText)==i.valueText){ "Некорректное переданное значение" }
     restoreSubmissionItems+=SubmissionItemEntity(s.id,i.meteringPointId,i.meterId,i.zone.uppercase(),i.valueText)
    }
   }
  }

  db.runInTransaction{
   dao.clearAddresses()
   if(ae.isNotEmpty())dao.insertAddresses(ae)
   if(pe.isNotEmpty())dao.insertMeteringPoints(pe.values.toList())
   if(me.isNotEmpty())dao.insertMeters(me)
   if(re.isNotEmpty())dao.insertReadings(re)
   if(ve.isNotEmpty())dao.insertReadingValues(ve)
   if(te.isNotEmpty())dao.insertTariffSchedule(te)
   if(preserve){
    if(preservedSubmissions.isNotEmpty())dao.insertSubmissions(preservedSubmissions)
    if(preservedSubmissionItems.isNotEmpty())dao.insertSubmissionItems(preservedSubmissionItems)
   }else{
    if(restoreSubmissionEntities.isNotEmpty())dao.insertSubmissions(restoreSubmissionEntities)
    if(restoreSubmissionItems.isNotEmpty())dao.insertSubmissionItems(restoreSubmissionItems)
   }
  }
 }

 private fun deleteOwnedPhoto(s:String){val u=runCatching{Uri.parse(s)}.getOrNull()?:return;if(u.scheme=="content"&&u.authority=="${context.packageName}.fileprovider")runCatching{context.contentResolver.delete(u,null,null)}}
 private fun migrateLegacyJsonIfNeeded(){if(legacyPrefs.getBoolean(FLAG,false))return;if(dao.addressCount()>0){legacyPrefs.edit().putBoolean(FLAG,true).commit();return};val raw=legacyPrefs.getString("addresses",null);if(raw.isNullOrBlank()){legacyPrefs.edit().putBoolean(FLAG,true).commit();return};val legacy=parseLegacy(raw)?:return;runCatching{replaceAll(legacy,false,emptyList())}.onSuccess{legacyPrefs.edit().putBoolean(FLAG,true).commit()}}
 private fun parseLegacy(raw:String):List<Address>?=runCatching{val root=JSONArray(raw);buildList{for(i in 0 until root.length()){val a=root.getJSONObject(i);val ma=a.optJSONArray("meters")?:JSONArray();add(Address(a.optString("id").ifBlank{UUID.randomUUID().toString()},a.optString("name","Адрес"),buildList{for(j in 0 until ma.length()){val m=ma.getJSONObject(j);val ra=m.optJSONArray("readings")?:JSONArray();val name=m.optString("name","Счётчик");add(Meter(id=m.optString("id").ifBlank{UUID.randomUUID().toString()},name=name,unit=m.optString("unit","ед."),kind=m.optString("kind").ifBlank{inferKind(name)},serial=m.optString("serial",""),readings=buildList{for(k in 0 until ra.length()){val r=ra.getJSONObject(k);val v=r.getDouble("value");val t=decimalText(v);add(Reading(r.optString("id").ifBlank{UUID.randomUUID().toString()},v,r.optLong("timestamp",System.currentTimeMillis()),r.optString("photoUri").takeIf{it.isNotBlank()},r.optString("note",""),t,false,mapOf("TOTAL" to t),null))}}))}}))}}}.getOrNull()
 private fun inferKind(n:String)=when{n.contains("холод",true)->"cold_water";n.contains("горяч",true)->"hot_water";n.contains("элект",true)->"electricity";n.contains("газ",true)->"gas";n.contains("отоп",true)->"heating";else->"other"}
 private fun defaultDigits(k:String)=when(k){"cold_water","hot_water","gas"->5 to 3;"electricity"->6 to 1;"heating"->6 to 3;else->6 to 2}
 private fun decimalText(v:Double)=BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
 private companion object{const val FLAG="room_migrated_v1"}
}
