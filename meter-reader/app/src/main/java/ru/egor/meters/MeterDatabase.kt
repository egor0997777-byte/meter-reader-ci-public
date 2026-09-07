package ru.egor.meters

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName="addresses") data class AddressEntity(@PrimaryKey val id:String,val name:String,val account:String="",val recipient:String="")
@Entity(
 tableName="metering_points",
 foreignKeys=[ForeignKey(entity=AddressEntity::class,parentColumns=["id"],childColumns=["addressId"],onDelete=ForeignKey.CASCADE)],
 indices=[Index("addressId")]
)
data class MeteringPointEntity(
 @PrimaryKey val id:String,
 val addressId:String,
 val name:String,
 val unit:String,
 val kind:String,
 val location:String="",
 val account:String="",
 val recipient:String="",
 val status:String="active"
)
@Entity(tableName="meters",foreignKeys=[ForeignKey(entity=AddressEntity::class,parentColumns=["id"],childColumns=["addressId"],onDelete=ForeignKey.CASCADE)],indices=[Index("addressId"),Index("previousMeterId"),Index("meteringPointId")])
data class MeterEntity(@PrimaryKey val id:String,val addressId:String,val name:String,val unit:String,val kind:String,val serial:String,val integerDigits:Int=6,val fractionDigits:Int=3,val previousMeterId:String?=null,val installedAt:Long?=null,val verificationUntil:Long?=null,val status:String="active",val account:String="",val recipient:String="",val tariffZones:String="TOTAL",val location:String="",val meteringPointId:String?=null)
@Entity(tableName="readings",foreignKeys=[ForeignKey(entity=MeterEntity::class,parentColumns=["id"],childColumns=["meterId"],onDelete=ForeignKey.CASCADE)],indices=[Index("meterId"),Index(value=["meterId","timestamp"]),Index(value=["meterId","billingPeriod"])])
data class ReadingEntity(@PrimaryKey val id:String,val meterId:String,val timestamp:Long,val photoUri:String?,val note:String,val source:String="manual",val billingPeriod:String?=null)
@Entity(tableName="reading_values",primaryKeys=["readingId","zone"],foreignKeys=[ForeignKey(entity=ReadingEntity::class,parentColumns=["id"],childColumns=["readingId"],onDelete=ForeignKey.CASCADE)],indices=[Index("readingId")]) data class ReadingValueEntity(val readingId:String,val zone:String="TOTAL",val valueText:String)
@Entity(tableName="tariff_schedule",primaryKeys=["meterId","zone","validFrom"],foreignKeys=[ForeignKey(entity=MeterEntity::class,parentColumns=["id"],childColumns=["meterId"],onDelete=ForeignKey.CASCADE)],indices=[Index("meterId")]) data class TariffScheduleEntity(val meterId:String,val zone:String,val validFrom:Long,val priceText:String)
@Entity(
 tableName="submissions",
 foreignKeys=[ForeignKey(entity=AddressEntity::class,parentColumns=["id"],childColumns=["addressId"],onDelete=ForeignKey.CASCADE)],
 indices=[Index("addressId"),Index(value=["addressId","billingPeriod"])]
)
data class SubmissionEntity(
 @PrimaryKey val id:String,
 val addressId:String,
 val billingPeriod:String,
 val submittedAt:Long,
 val recipient:String="",
 val snapshotText:String=""
)
@Entity(
 tableName="submission_items",
 primaryKeys=["submissionId","meteringPointId","zone"],
 foreignKeys=[ForeignKey(entity=SubmissionEntity::class,parentColumns=["id"],childColumns=["submissionId"],onDelete=ForeignKey.CASCADE)],
 indices=[Index("submissionId"),Index("meteringPointId"),Index("meterId")]
)
data class SubmissionItemEntity(
 val submissionId:String,
 val meteringPointId:String,
 val meterId:String,
 val zone:String="TOTAL",
 val valueText:String
)

@Dao interface MeterDao{
 @Query("SELECT * FROM addresses ORDER BY rowid") fun addresses():List<AddressEntity>
 @Query("SELECT * FROM metering_points ORDER BY rowid") fun meteringPoints():List<MeteringPointEntity>
 @Query("SELECT * FROM meters ORDER BY rowid") fun meters():List<MeterEntity>
 @Query("SELECT * FROM readings ORDER BY timestamp") fun readings():List<ReadingEntity>
 @Query("SELECT * FROM reading_values") fun readingValues():List<ReadingValueEntity>
 @Query("SELECT * FROM tariff_schedule ORDER BY validFrom") fun tariffSchedule():List<TariffScheduleEntity>
 @Query("SELECT * FROM submissions ORDER BY submittedAt") fun submissions():List<SubmissionEntity>
 @Query("SELECT * FROM submission_items") fun submissionItems():List<SubmissionItemEntity>
 @Query("SELECT COUNT(*) FROM addresses") fun addressCount():Int
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertAddresses(items:List<AddressEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertMeteringPoints(items:List<MeteringPointEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertMeters(items:List<MeterEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertReadings(items:List<ReadingEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertReadingValues(items:List<ReadingValueEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertTariffSchedule(items:List<TariffScheduleEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertSubmissions(items:List<SubmissionEntity>)
 @Insert(onConflict=OnConflictStrategy.REPLACE) fun insertSubmissionItems(items:List<SubmissionItemEntity>)
 @Query("DELETE FROM addresses") fun clearAddresses()
}

@Database(entities=[AddressEntity::class,MeteringPointEntity::class,MeterEntity::class,ReadingEntity::class,ReadingValueEntity::class,TariffScheduleEntity::class,SubmissionEntity::class,SubmissionItemEntity::class],version=5,exportSchema=true)
abstract class MeterDatabase:RoomDatabase(){abstract fun meterDao():MeterDao
 companion object{
  @Volatile private var instance:MeterDatabase?=null
  val MIGRATION_1_2=object:Migration(1,2){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE addresses ADD COLUMN account TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE addresses ADD COLUMN recipient TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE meters ADD COLUMN account TEXT NOT NULL DEFAULT ''");db.execSQL("ALTER TABLE meters ADD COLUMN recipient TEXT NOT NULL DEFAULT ''")}}
  val MIGRATION_2_3=object:Migration(2,3){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE meters ADD COLUMN tariffZones TEXT NOT NULL DEFAULT 'TOTAL'");db.execSQL("CREATE TABLE IF NOT EXISTS tariff_schedule (meterId TEXT NOT NULL, zone TEXT NOT NULL, validFrom INTEGER NOT NULL, priceText TEXT NOT NULL, PRIMARY KEY(meterId, zone, validFrom), FOREIGN KEY(meterId) REFERENCES meters(id) ON UPDATE NO ACTION ON DELETE CASCADE)");db.execSQL("CREATE INDEX IF NOT EXISTS index_tariff_schedule_meterId ON tariff_schedule(meterId)")}}
  val MIGRATION_3_4=object:Migration(3,4){override fun migrate(db:SupportSQLiteDatabase){db.execSQL("ALTER TABLE meters ADD COLUMN location TEXT NOT NULL DEFAULT ''")}}
  val MIGRATION_4_5=object:Migration(4,5){override fun migrate(db:SupportSQLiteDatabase){
   db.execSQL("CREATE TABLE IF NOT EXISTS metering_points (id TEXT NOT NULL, addressId TEXT NOT NULL, name TEXT NOT NULL, unit TEXT NOT NULL, kind TEXT NOT NULL, location TEXT NOT NULL, account TEXT NOT NULL, recipient TEXT NOT NULL, status TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(addressId) REFERENCES addresses(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_metering_points_addressId ON metering_points(addressId)")
   db.execSQL("ALTER TABLE meters ADD COLUMN meteringPointId TEXT")
   val previousById=linkedMapOf<String,String?>()
   val details=linkedMapOf<String,Array<String>>()
   db.query("SELECT id,addressId,name,unit,kind,location,account,recipient,status,previousMeterId FROM meters ORDER BY rowid").use{c->
    while(c.moveToNext()){
     val id=c.getString(0)
     previousById[id]=if(c.isNull(9)) null else c.getString(9)
     details[id]=arrayOf(c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getString(6),c.getString(7),c.getString(8))
    }
   }
   fun rootOf(id:String):String{
    var current=id
    val seen=mutableSetOf<String>()
    while(seen.add(current)){
     val previous=previousById[current] ?: return current
     if(!previousById.containsKey(previous)) return current
     current=previous
    }
    return id
   }
   details.forEach{(id,values)->
    val pointId=rootOf(id)
    db.execSQL("INSERT OR REPLACE INTO metering_points(id,addressId,name,unit,kind,location,account,recipient,status) VALUES(?,?,?,?,?,?,?,?,?)",arrayOf(pointId,values[0],values[1],values[2],values[3],values[4],values[5],values[6],values[7]))
    db.execSQL("UPDATE meters SET meteringPointId=? WHERE id=?",arrayOf(pointId,id))
   }
   db.execSQL("CREATE INDEX IF NOT EXISTS index_meters_meteringPointId ON meters(meteringPointId)")
   db.execSQL("ALTER TABLE readings ADD COLUMN billingPeriod TEXT")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_readings_meterId_billingPeriod ON readings(meterId,billingPeriod)")
   db.execSQL("CREATE TABLE IF NOT EXISTS submissions (id TEXT NOT NULL, addressId TEXT NOT NULL, billingPeriod TEXT NOT NULL, submittedAt INTEGER NOT NULL, recipient TEXT NOT NULL, snapshotText TEXT NOT NULL, PRIMARY KEY(id), FOREIGN KEY(addressId) REFERENCES addresses(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_submissions_addressId ON submissions(addressId)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_submissions_addressId_billingPeriod ON submissions(addressId,billingPeriod)")
   db.execSQL("CREATE TABLE IF NOT EXISTS submission_items (submissionId TEXT NOT NULL, meteringPointId TEXT NOT NULL, meterId TEXT NOT NULL, zone TEXT NOT NULL, valueText TEXT NOT NULL, PRIMARY KEY(submissionId,meteringPointId,zone), FOREIGN KEY(submissionId) REFERENCES submissions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_submission_items_submissionId ON submission_items(submissionId)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_submission_items_meteringPointId ON submission_items(meteringPointId)")
   db.execSQL("CREATE INDEX IF NOT EXISTS index_submission_items_meterId ON submission_items(meterId)")
  }}
  private fun builder(context:Context,name:String)=Room.databaseBuilder(context.applicationContext,MeterDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5).allowMainThreadQueries()
  fun get(context:Context):MeterDatabase=instance?:synchronized(this){instance?:builder(context,"meter-reader.db").build().also{instance=it}}
  fun openTemporary(context:Context,name:String):MeterDatabase=builder(context,name).build()
 }
}