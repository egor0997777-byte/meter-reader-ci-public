package ru.egor.meters

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val A07 = Color(0xFF6B5BC7)
private val BG07 = Color(0xFFF7F7FA)
private val S07 = Color(0xFFF1F0F5)
private val M07 = Color(0xFF77737F)
private val D07 = Color(0xFFC33E49)
private val C07 = lightColorScheme(primary=A07, background=BG07, surface=Color.White, surfaceVariant=S07, error=D07)

private data class P07(val title:String,val unit:String,val kind:String,val mark:String)
private val P07S = listOf(
    P07("Холодная вода","м³","cold_water","ХВ"), P07("Горячая вода","м³","hot_water","ГВ"),
    P07("Электричество","кВт·ч","electricity","ЭЭ"), P07("Газ","м³","gas","Г"),
    P07("Отопление","Гкал","heating","Т"), P07("Другой","ед.","other","•")
)

class V07MainActivity : ComponentActivity() {
    private var pendingPhotoUri: Uri? = null
    private var photoCallback: ((String?) -> Unit)? = null
    private var pendingBackup: ByteArray? = null
    private var pendingCsv: String? = null
    private var restoreCallback: ((Result<BackupPayload>) -> Unit)? = null

    private val camera = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingPhotoUri
        if (!ok && uri != null) runCatching { contentResolver.delete(uri,null,null) }
        photoCallback?.invoke(if(ok) uri?.toString() else null)
        pendingPhotoUri = null
        photoCallback = null
    }
    private val backupSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val bytes=pendingBackup; pendingBackup=null
        if(uri!=null && bytes!=null) runCatching { contentResolver.openOutputStream(uri)?.use{it.write(bytes)} }
    }
    private val csvSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val text=pendingCsv; pendingCsv=null
        if(uri!=null && text!=null) runCatching { contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray(Charsets.UTF_8))} }
    }
    private val backupPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null){
            val result=runCatching {
                val bytes=contentResolver.openInputStream(uri)?.use{it.readBytes()} ?: error("Не удалось прочитать файл")
                MeterTransfer.parseBackup(bytes)
            }
            restoreCallback?.invoke(result)
        }
        restoreCallback=null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo=MeterRepository(this)
        setContent {
            MaterialTheme(colorScheme=C07) {
                App07(
                    repo=repo,
                    takePhoto={cb->takePhoto(cb)},
                    saveBackup={data->
                        val bytes=MeterTransfer.createBackup(this,data,repo.submissions())
                        MeterTransfer.rememberLocalGeneration(this,bytes)
                        pendingBackup=bytes
                        backupSaver.launch("moi-schetschiki-backup.zip")
                    },
                    pickBackup={cb->restoreCallback=cb;backupPicker.launch(arrayOf("application/zip","application/octet-stream"))},
                    saveCsv={data->pendingCsv=MeterTransfer.csv(data);csvSaver.launch("moi-schetschiki-history.csv")}
                )
            }
        }
    }

    private fun takePhoto(cb:(String?)->Unit){
        val dir=getExternalFilesDir(Environment.DIRECTORY_PICTURES)?:filesDir
        val file=File(dir,"meter_${System.currentTimeMillis()}.jpg")
        val uri=FileProvider.getUriForFile(this,"$packageName.fileprovider",file)
        pendingPhotoUri=uri; photoCallback=cb; camera.launch(uri)
    }
}

@Composable
private fun App07(
    repo:MeterRepository,
    takePhoto:(((String?)->Unit)->Unit),
    saveBackup:(List<Address>)->Unit,
    pickBackup:((Result<BackupPayload>)->Unit)->Unit,
    saveCsv:(List<Address>)->Unit
){
    val ctx=LocalContext.current
    var data by remember{ mutableStateOf(repo.load()) }
    var addressId by remember{ mutableStateOf<String?>(null) }
    var meterId by remember{ mutableStateOf<String?>(null) }
    var tools by remember{ mutableStateOf(false) }
    var pendingRestore by remember{ mutableStateOf<BackupPayload?>(null) }
    var message by remember{ mutableStateOf<String?>(null) }
    fun save(v:List<Address>){data=v;repo.save(v)}
    val address=data.firstOrNull{it.id==addressId}
    val meter=address?.meters?.firstOrNull{it.id==meterId}

    Surface(Modifier.fillMaxSize(),color=BG07){
        when {
            tools -> Tools07(data,{tools=false},{saveBackup(data)},{pickBackup{r->r.onSuccess{pendingRestore=it}.onFailure{message=it.message?:"Файл повреждён"}}},{saveCsv(data)})
            address==null -> Home07(data,{addressId=it},{save(data+Address(name=it))},{id->save(data.filterNot{it.id==id})},{tools=true})
            meter==null -> Address07(
                address=address,
                back={addressId=null}, open={meterId=it},
                add={p,n,s,acc,rec->save(data.map{x->if(x.id==address.id)x.copy(meters=x.meters+Meter(name=n,unit=p.unit,kind=p.kind,serial=s,account=acc,recipient=rec))else x})},
                edit={changed->save(data.map{x->if(x.id==address.id)x.copy(meters=x.meters.map{if(it.id==changed.id)changed else it})else x})},
                editAddress={changed->save(data.map{if(it.id==changed.id)changed else it})},
                replace={old,serial,initial->
                    val raw=MeterHistory.normalizeReadingText(initial)!!; val now=System.currentTimeMillis()
                    val replacement=Meter(name=old.name,unit=old.unit,kind=old.kind,serial=serial,readings=listOf(Reading(value=raw.toDouble(),valueText=raw,timestamp=now)),integerDigits=old.integerDigits,fractionDigits=old.fractionDigits,previousMeterId=old.id,installedAt=now,status="active",account=old.account,recipient=old.recipient)
                    save(data.map{x->if(x.id==address.id)x.copy(meters=x.meters.map{if(it.id==old.id)it.copy(status="closed")else it}+replacement)else x})
                },
                delete={id->save(data.map{x->if(x.id==address.id)x.copy(meters=x.meters.filterNot{it.id==id})else x})}
            )
            else -> Meter07(
                m=meter, back={meterId=null}, take=takePhoto,
                add={r->save(data.map{x->if(x.id==address.id)x.copy(meters=x.meters.map{if(it.id==meter.id)it.copy(readings=it.readings+r)else it})else x})},
                edit={r->save(data.map{x->if(x.id==address.id)x.copy(meters=x.meters.map{mm->if(mm.id==meter.id)mm.copy(readings=mm.readings.map{if(it.id==r.id)r else it})else mm})else x})},
                delete={id->save(data.map{x->if(x.id==address.id)x.copy(meters=x.meters.map{mm->if(mm.id==meter.id)mm.copy(readings=mm.readings.filterNot{it.id==id})else mm})else x})}
            )
        }
    }

    pendingRestore?.let { payload ->
        ConfirmAction07(
            title="Восстановить резервную копию?",
            body="Текущие адреса и показания будут заменены только после подтверждения. Файл уже проверен.",
            action="Восстановить",
            dismiss={pendingRestore=null},
            confirm={
                val result=runCatching {
                    val prepared=MeterTransfer.prepareRestorePhotos(ctx,payload)
                    try {
                        repo.restoreValidated(prepared.addresses,payload.submissions)
                    } catch(t:Throwable) {
                        MeterTransfer.abortPreparedRestore(prepared)
                        throw t
                    }
                }
                result.onSuccess {
                    data=repo.load();addressId=null;meterId=null;tools=false;pendingRestore=null;message="Данные восстановлены"
                }.onFailure {
                    pendingRestore=null;message=it.message?:"Не удалось восстановить резервную копию"
                }
            }
        )
    }
    message?.let { msg -> AlertDialog(onDismissRequest={message=null},title={Text("Мои счётчики")},text={Text(msg)},confirmButton={TextButton({message=null}){Text("OK")}}) }
}

@Composable private fun Frame07(content:@Composable ColumnScope.()->Unit)=Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal=18.dp,vertical=8.dp),content=content)

@Composable
private fun Home07(data:List<Address>,open:(String)->Unit,add:(String)->Unit,delete:(String)->Unit,tools:()->Unit){
    var addDialog by remember{mutableStateOf(false)};var deleting by remember{mutableStateOf<Address?>(null)}
    Frame07{
        Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Мои счётчики",fontSize=29.sp,fontWeight=FontWeight.Bold);Text("Показания, история и фото",color=M07,fontSize=14.sp)};TextButton(tools){Text("Данные")}}
        Spacer(Modifier.height(16.dp))
        if(data.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Empty07("Добавьте первый адрес","Квартира, дом или дача — всё хранится локально.")}
        else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)){items(data,key={it.id}){a->
            Card(onClick={open(a.id)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){Row(Modifier.padding(start=15.dp,top=10.dp,bottom=10.dp,end=7.dp),verticalAlignment=Alignment.CenterVertically){Badge07("⌂");Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(a.name,fontWeight=FontWeight.SemiBold,fontSize=16.sp);Text("${a.meters.count{it.status!="closed"}} активных",color=M07,fontSize=12.sp)};TextButton(onClick={deleting=a}){Text("Удалить",color=D07,fontSize=12.sp)}}}
        }}
        Spacer(Modifier.height(10.dp));Primary07("Добавить адрес"){addDialog=true}
    }
    if(addDialog)TextDialog07("Новый адрес","Название или адрес","Добавить",{addDialog=false}){add(it);addDialog=false}
    deleting?.let{t->Confirm07("Удалить адрес?","Все счётчики и показания по адресу «${t.name}» будут удалены.",{deleting=null},{delete(t.id);deleting=null})}
}

@Composable
private fun Tools07(data:List<Address>,back:()->Unit,backup:()->Unit,restore:()->Unit,csv:()->Unit){
    val ctx=LocalContext.current
    Frame07{
        Back07("Мои счётчики",back);Text("Данные и передача",fontSize=27.sp,fontWeight=FontWeight.Bold);Text("Всё работает локально, без аккаунта и сервера.",color=M07,fontSize=13.sp);Spacer(Modifier.height(18.dp))
        Button(backup,Modifier.fillMaxWidth()){Text("Создать резервную копию")};Spacer(Modifier.height(8.dp));OutlinedButton(restore,Modifier.fillMaxWidth()){Text("Восстановить из копии")};Spacer(Modifier.height(8.dp));OutlinedButton(csv,Modifier.fillMaxWidth()){Text("Экспорт истории CSV")};Spacer(Modifier.height(18.dp))
        Text("Передача показаний",fontWeight=FontWeight.Bold);Text("Приложение только готовит текст — автоматической отправки в УК/РСО нет.",color=M07,fontSize=12.sp);Spacer(Modifier.height(8.dp))
        data.forEach { a ->
            val text=MeterTransfer.transmissionText(a)
            Surface(Modifier.fillMaxWidth().padding(bottom=8.dp),shape=RoundedCornerShape(16.dp),color=Color.White){Column(Modifier.padding(12.dp)){Text(a.name,fontWeight=FontWeight.SemiBold);Row{TextButton(onClick={val cm=ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cm.setPrimaryClip(ClipData.newPlainText("Показания",text))}){Text("Скопировать")};TextButton(onClick={ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,text)},"Передать показания"))}){Text("Поделиться")}}}}
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Address07(address:Address,back:()->Unit,open:(String)->Unit,add:(P07,String,String,String,String)->Unit,edit:(Meter)->Unit,editAddress:(Address)->Unit,replace:(Meter,String,String)->Unit,delete:(String)->Unit){
    var addDialog by remember{mutableStateOf(false)};var editing by remember{mutableStateOf<Meter?>(null)};var metadata by remember{mutableStateOf(false)};var replacing by remember{mutableStateOf<Meter?>(null)};var deleting by remember{mutableStateOf<Meter?>(null)}
    Frame07{
        Back07("Все адреса",back);Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(address.name,fontSize=27.sp,fontWeight=FontWeight.Bold);Text("${address.meters.size} ${plural07(address.meters.size,"счётчик","счётчика","счётчиков")}",color=M07)};TextButton({metadata=true}){Text("Реквизиты")}};Spacer(Modifier.height(14.dp))
        if(address.meters.isEmpty())Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center){Empty07("Добавьте счётчик","Тип, название и серийный номер можно изменить позже.")}
        else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(9.dp)){items(address.meters,key={it.id}){m->
            val successor=address.meters.any{it.previousMeterId==m.id};val closed=m.status=="closed"
            Card(onClick={open(m.id)},modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp)){Column(Modifier.padding(14.dp)){Row(verticalAlignment=Alignment.CenterVertically){Badge07(mark07(m.kind));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(m.name,fontWeight=FontWeight.SemiBold);if(m.serial.isNotBlank())Text("№ ${m.serial}",color=M07,fontSize=11.sp);if(closed)Text("Заменён · история сохранена",color=M07,fontSize=11.sp)};if(!closed)TextButton({replacing=m},contentPadding=PaddingValues(horizontal=6.dp)){Text("Заменить",fontSize=11.sp)};TextButton({editing=m},contentPadding=PaddingValues(horizontal=6.dp)){Text("Изменить",fontSize=11.sp)};if(!successor)TextButton({deleting=m},contentPadding=PaddingValues(horizontal=6.dp)){Text("Удалить",color=D07,fontSize=11.sp)}};val last=m.readings.maxByOrNull{it.timestamp};if(last==null)Text("Нет показаний",color=M07,fontSize=12.sp)else Text("${MeterHistory.displayText(last)} ${m.unit}",fontSize=23.sp,fontWeight=FontWeight.Bold)}}
        }}
        Spacer(Modifier.height(10.dp));Primary07("Добавить счётчик"){addDialog=true}
    }
    if(addDialog)MeterDialog07(null,{addDialog=false}){p,n,s,acc,rec->add(p,n,s,acc,rec);addDialog=false}
    editing?.let{old->MeterDialog07(old,{editing=null}){p,n,s,acc,rec->edit(old.copy(name=n,unit=p.unit,kind=p.kind,serial=s,account=acc,recipient=rec));editing=null}}
    if(metadata)AddressMetaDialog07(address,{metadata=false}){editAddress(it);metadata=false}
    replacing?.let{old->ReplacementDialog07(old,{replacing=null}){serial,initial->replace(old,serial,initial);replacing=null}}
    deleting?.let{t->Confirm07("Удалить счётчик?","История «${t.name}» тоже будет удалена.",{deleting=null},{delete(t.id);deleting=null})}
}

@Composable
private fun Meter07(m:Meter,back:()->Unit,take:(((String?)->Unit)->Unit),add:(Reading)->Unit,edit:(Reading)->Unit,delete:(String)->Unit){
    var addDialog by remember{mutableStateOf(false)};var editing by remember{mutableStateOf<Reading?>(null)};var deleting by remember{mutableStateOf<Reading?>(null)}
    val digits=m.integerDigits?:6;val history=MeterHistory.history(m.readings,digits);val byId=history.associateBy{it.reading.id};val sorted=m.readings.sortedByDescending{it.timestamp};val last=sorted.firstOrNull();val lastConsumption=last?.let{byId[it.id]?.consumption}
    Frame07{
        Back07("Счётчики",back);Row(verticalAlignment=Alignment.CenterVertically){Badge07(mark07(m.kind));Spacer(Modifier.width(10.dp));Column{Text(m.name,fontSize=26.sp,fontWeight=FontWeight.Bold);if(m.serial.isNotBlank())Text("№ ${m.serial}",color=M07,fontSize=12.sp);if(m.status=="closed")Text("Счётчик заменён · только история",color=M07,fontSize=11.sp)}};Spacer(Modifier.height(14.dp))
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp),color=Color.White){Column(Modifier.padding(16.dp)){Text("Последнее показание",color=M07,fontSize=12.sp);Text(if(last==null)"—" else "${MeterHistory.displayText(last)} ${m.unit}",fontSize=32.sp,fontWeight=FontWeight.Bold);if(last!=null)Text(dt07(last.timestamp),color=M07,fontSize=12.sp);lastConsumption?.amount?.let{Text("Расход ${MeterHistory.format(it)} ${m.unit}${if(lastConsumption.rollover)" · после переполнения" else ""}",color=A07,fontSize=12.sp)}}};Spacer(Modifier.height(16.dp));Text("История",fontSize=18.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp))
        if(sorted.isEmpty()){Text("Здесь появятся сохранённые показания.",color=M07);Spacer(Modifier.weight(1f))}
        else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)){items(sorted,key={it.id}){r->
            val c=byId[r.id]?.consumption
            Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),color=Color.White){Column(Modifier.padding(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("${MeterHistory.displayText(r)} ${m.unit}",fontWeight=FontWeight.SemiBold);Text(dt07(r.timestamp),color=M07,fontSize=11.sp);c?.amount?.let{Text("Расход ${MeterHistory.format(it)} ${m.unit}${if(c.rollover)" · переполнение" else ""}",color=A07,fontSize=11.sp)};if(c?.lowerThanPrevious==true)Text("Меньше предыдущего — проверьте запись",color=D07,fontSize=11.sp)};TextButton({editing=r},contentPadding=PaddingValues(horizontal=7.dp)){Text("Изменить",fontSize=12.sp)};TextButton({deleting=r},contentPadding=PaddingValues(horizontal=7.dp)){Text("Удалить",color=D07,fontSize=12.sp)}};if(r.note.isNotBlank())Text(r.note,color=M07,fontSize=11.sp);if(r.photoUri!=null){Spacer(Modifier.height(8.dp));Photo07(r.photoUri,72)}}}
        }}
        if(m.status!="closed"){Spacer(Modifier.height(10.dp));Primary07("Новое показание"){addDialog=true}}
    }
    if(addDialog)ReadingDialog07(m,null,take,{addDialog=false}){v,raw,roll,p,n->add(Reading(value=v,valueText=raw,rollover=roll,photoUri=p,note=n));addDialog=false}}
    editing?.let{old->ReadingDialog07(m,old,take,{editing=null}){v,raw,roll,p,n->edit(old.copy(value=v,valueText=raw,rollover=roll,photoUri=p,note=n));editing=null}}
    deleting?.let{t->Confirm07("Удалить показание?","Расход последующих записей будет автоматически пересчитан.",{deleting=null},{delete(t.id);deleting=null})}
}

@Composable
private fun MeterDialog07(old:Meter?,dismiss:()->Unit,confirm:(P07,String,String,String,String)->Unit){
    var p by remember{mutableStateOf(P07S.firstOrNull{it.kind==old?.kind}?:P07S.first())};var name by remember{mutableStateOf(old?.name?:p.title)};var serial by remember{mutableStateOf(old?.serial?:"")};var account by remember{mutableStateOf(old?.account?:"")};var recipient by remember{mutableStateOf(old?.recipient?:"")}
    Dialog(onDismissRequest=dismiss){
        Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(26.dp)){
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())){
                Text(if(old==null)"Новый счётчик" else "Изменить счётчик",fontSize=24.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp))
                P07S.chunked(2).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{x->FilterChip(selected=x.kind==p.kind,onClick={p=x;if(old==null)name=x.title},label={Text(x.mark+"  "+x.title)},modifier=Modifier.weight(1f))}};Spacer(Modifier.height(6.dp))}
                Field07(name,{name=it.take(50)},"Название");Spacer(Modifier.height(8.dp));Field07(serial,{serial=it.take(40)},"Серийный номер","Необязательно",KeyboardOptions(capitalization=KeyboardCapitalization.Characters,keyboardType=KeyboardType.Text,imeAction=ImeAction.Next,autoCorrectEnabled=false));Spacer(Modifier.height(8.dp));Field07(account,{account=it.take(50)},"Лицевой счёт","Можно оставить у адреса");Spacer(Modifier.height(8.dp));Field07(recipient,{recipient=it.take(80)},"УК / РСО / получатель","Необязательно");Actions07(dismiss,{confirm(p,name.trim(),serial.trim(),account.trim(),recipient.trim())},if(old==null)"Добавить" else "Сохранить",name.isNotBlank())
            }
        }
    }
}

@Composable private fun AddressMetaDialog07(old:Address,dismiss:()->Unit,confirm:(Address)->Unit){
    var account by remember{mutableStateOf(old.account)};var recipient by remember{mutableStateOf(old.recipient)}
    Dialog(onDismissRequest=dismiss){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp)){Text("Реквизиты адреса",fontSize=23.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));Field07(account,{account=it.take(50)},"Лицевой счёт","Необязательно");Spacer(Modifier.height(8.dp));Field07(recipient,{recipient=it.take(80)},"УК / РСО / получатель","Необязательно");Actions07(dismiss,{confirm(old.copy(account=account.trim(),recipient=recipient.trim()))},"Сохранить",true)}}}
}

@Composable private fun ReplacementDialog07(old:Meter,dismiss:()->Unit,confirm:(String,String)->Unit){
    var serial by remember{mutableStateOf("")};var initial by remember{mutableStateOf("")};val normalized=MeterHistory.normalizeReadingText(initial)
    Dialog(onDismissRequest=dismiss){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(26.dp)){Column(Modifier.padding(18.dp)){Text("Замена счётчика",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("История старого счётчика останется без изменений.",color=M07,fontSize=12.sp);Spacer(Modifier.height(12.dp));Field07(serial,{serial=it.take(40)},"Новый серийный номер","Необязательно");Spacer(Modifier.height(8.dp));OutlinedTextField(initial,{initial=sanitize07(it)},Modifier.fillMaxWidth(),label={Text("Начальное показание")},suffix={Text(old.unit)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),shape=RoundedCornerShape(16.dp));Actions07(dismiss,{if(normalized!=null)confirm(serial.trim(),normalized)},"Заменить",normalized!=null)}}}
}

@Composable
private fun ReadingDialog07(m:Meter,old:Reading?,take:(((String?)->Unit)->Unit),dismiss:()->Unit,confirm:(Double,String,Boolean,String?,String)->Unit){
    val ctx=LocalContext.current;val chronological=m.readings.filterNot{it.id==old?.id}.sortedBy{it.timestamp};val previous=if(old==null)chronological.lastOrNull()else chronological.lastOrNull{it.timestamp<old.timestamp};val digits=m.integerDigits?:6
    var text by remember{mutableStateOf(old?.let{MeterHistory.displayText(it)}?:"")};var photo by remember{mutableStateOf(old?.photoUri)};var note by remember{mutableStateOf(old?.note?:"")};var busy by remember{mutableStateOf(false)};var status by remember{mutableStateOf<String?>(if(old==null)null else "Расход соседних записей пересчитается автоматически.")};var rolloverOk by remember{mutableStateOf(old?.rollover?:false)};var anomalyOk by remember{mutableStateOf(false)}
    val normalized=MeterHistory.normalizeReadingText(text);val parsed=normalized?.toDoubleOrNull();val previousText=previous?.let{MeterHistory.readingText(it)};val calc=if(normalized!=null&&previousText!=null)MeterHistory.consumption(previousText,normalized,digits,rolloverOk)else null;val lower=calc?.lowerThanPrevious==true;val recentAmounts=MeterHistory.history(m.readings.filterNot{it.id==old?.id},digits).mapNotNull{it.consumption?.amount};val anomalous=calc?.amount?.let{MeterHistory.isAnomalous(it,recentAmounts)}==true
    Dialog(onDismissRequest={if(!busy)dismiss()}){
        Surface(Modifier.fillMaxWidth().heightIn(max=720.dp),shape=RoundedCornerShape(26.dp)){
            Column(Modifier.padding(18.dp).verticalScroll(rememberScrollState())){
                Text(if(old==null)"Новое показание" else "Изменить показание",fontSize=24.sp,fontWeight=FontWeight.Bold);Text(m.name,color=M07,fontSize=13.sp);Spacer(Modifier.height(12.dp))
                photo?.let{current->Photo07(current,150);Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){TextButton({photo=null;status="Фото будет удалено после сохранения."},enabled=!busy){Text("Удалить фото",color=D07)}}}
                Button(onClick={take{u->if(u!=null){photo=u;busy=true;status="Распознаю цифры…";MeterOcr.recognize(ctx,u,previous?.value){res->busy=false;res.onSuccess{v->if(v!=null){text=val07(v);status="OCR нашёл ${val07(v)}. Проверьте значение перед сохранением."}else status="Число не найдено — введите вручную."}.onFailure{status="OCR не справился — введите вручную."}}}}},enabled=!busy,modifier=Modifier.fillMaxWidth(),colors=ButtonDefaults.buttonColors(containerColor=S07,contentColor=A07)){Text(if(photo==null)"📷  Сфотографировать счётчик" else "📷  Переснять фото")}
                if(busy){Spacer(Modifier.height(6.dp));LinearProgressIndicator(Modifier.fillMaxWidth())};status?.let{Text(it,color=M07,fontSize=11.sp,modifier=Modifier.padding(top=6.dp))};Spacer(Modifier.height(12.dp))
                OutlinedTextField(text,{text=sanitize07(it);rolloverOk=false;anomalyOk=false},Modifier.fillMaxWidth(),label={Text("Показание")},suffix={Text(m.unit)},singleLine=true,textStyle=LocalTextStyle.current.copy(fontSize=26.sp,fontWeight=FontWeight.SemiBold),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),shape=RoundedCornerShape(16.dp))
                previous?.let{Text("Предыдущее: ${MeterHistory.displayText(it)} ${m.unit}",color=M07,fontSize=11.sp)};calc?.amount?.let{Text("Расход: ${MeterHistory.format(it)} ${m.unit}",color=A07,fontSize=12.sp)}
                if(lower){Text("Показание меньше предыдущего. Если это не переполнение механического счётчика — исправьте цифры или используйте «Заменить счётчик».",color=D07,fontSize=11.sp);Row(verticalAlignment=Alignment.CenterVertically){Checkbox(rolloverOk,{rolloverOk=it});Text("Подтвердить переполнение счётчика",fontSize=12.sp)}}
                if(anomalous){Text("Расход заметно выше обычного.",color=D07,fontSize=11.sp);Row(verticalAlignment=Alignment.CenterVertically){Checkbox(anomalyOk,{anomalyOk=it});Text("Показание проверено, сохранить",fontSize=12.sp)}}
                Spacer(Modifier.height(8.dp));Field07(note,{note=it.take(200)},"Комментарий","Необязательно",single=false);Actions07(dismiss,{if(parsed!=null&&normalized!=null)confirm(parsed,normalized,rolloverOk,photo,note.trim())},"Сохранить",parsed!=null&&!busy&&(!lower||rolloverOk)&&(!anomalous||anomalyOk))
            }
        }
    }
}

@Composable private fun Photo07(uri:String,height:Int){val ctx=LocalContext.current;val bmp=remember(uri){runCatching{ctx.contentResolver.openInputStream(Uri.parse(uri))?.use{BitmapFactory.decodeStream(it)}}.getOrNull()};if(bmp!=null)Image(bmp.asImageBitmap(),null,Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Crop)else Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(14.dp),color=S07){Text("Фото сохранено",Modifier.padding(12.dp),color=M07)}}
@Composable private fun TextDialog07(title:String,label:String,button:String,onDismiss:()->Unit,onConfirm:(String)->Unit){var t by remember{mutableStateOf("")};Dialog(onDismissRequest=onDismiss){Surface(shape=RoundedCornerShape(24.dp)){Column(Modifier.padding(18.dp)){Text(title,fontSize=23.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(12.dp));Field07(t,{t=it.take(80)},label);Actions07(onDismiss,{onConfirm(t.trim())},button,t.isNotBlank())}}}}
@Composable private fun Confirm07(title:String,body:String,dismiss:()->Unit,confirm:()->Unit)=ConfirmAction07(title,body,"Удалить",dismiss,confirm,true)
@Composable private fun ConfirmAction07(title:String,body:String,action:String,dismiss:()->Unit,confirm:()->Unit,danger:Boolean=false){AlertDialog(onDismissRequest=dismiss,title={Text(title)},text={Text(body)},confirmButton={TextButton(confirm){Text(action,color=if(danger)D07 else A07)}},dismissButton={TextButton(dismiss){Text("Отмена")}})}
@Composable private fun Field07(v:String,change:(String)->Unit,label:String,placeholder:String="",keys:KeyboardOptions=KeyboardOptions.Default,single:Boolean=true)=OutlinedTextField(v,change,Modifier.fillMaxWidth(),label={Text(label)},placeholder={if(placeholder.isNotBlank())Text(placeholder)},singleLine=single,minLines=if(single)1 else 2,keyboardOptions=keys,shape=RoundedCornerShape(16.dp))
@Composable private fun Actions07(cancel:()->Unit,ok:()->Unit,text:String,enabled:Boolean){Spacer(Modifier.height(14.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(cancel,Modifier.weight(1f)){Text("Отмена")};Button(ok,Modifier.weight(1f),enabled=enabled){Text(text)}}}
@Composable private fun Primary07(t:String,c:()->Unit)=Button(c,Modifier.fillMaxWidth().height(52.dp),shape=RoundedCornerShape(17.dp)){Text(t,fontWeight=FontWeight.SemiBold)}
@Composable private fun Back07(t:String,c:()->Unit)=TextButton(c,contentPadding=PaddingValues(0.dp)){Text("‹  $t",color=A07)}
@Composable private fun Empty07(t:String,s:String)=Column(horizontalAlignment=Alignment.CenterHorizontally){Text(t,fontSize=20.sp,fontWeight=FontWeight.Bold);Text(s,color=M07,fontSize=13.sp)}
@Composable private fun Badge07(t:String)=Box(Modifier.size(42.dp).background(A07.copy(alpha=.10f),RoundedCornerShape(21.dp)),contentAlignment=Alignment.Center){Text(t,color=A07,fontWeight=FontWeight.Bold,fontSize=12.sp)}
private fun mark07(k:String)=P07S.firstOrNull{it.kind==k}?.mark?:"•"
private fun sanitize07(raw:String):String{val out=StringBuilder();var sep=false;raw.replace('.',',').forEach{c->when{c.isDigit()->out.append(c);c==','&&!sep->{if(out.isEmpty())out.append('0');out.append(',');sep=true}}};return out.toString().take(14)}
private fun val07(v:Double)=if(v%1.0==0.0)v.toLong().toString() else String.format(Locale.getDefault(),"%.3f",v).trimEnd('0').trimEnd(',','.')
private fun dt07(t:Long)=SimpleDateFormat("dd.MM.yyyy HH:mm",Locale.getDefault()).format(Date(t))
private fun plural07(n:Int,one:String,few:String,many:String):String{val a=n%100;val b=n%10;return when{a in 11..14->many;b==1->one;b in 2..4->few;else->many}}
