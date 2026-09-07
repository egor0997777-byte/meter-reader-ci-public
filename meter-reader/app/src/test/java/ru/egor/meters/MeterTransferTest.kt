package ru.egor.meters

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MeterTransferTest {
    private val sample = listOf(Address(id="a1",name="Дом",account="ЛС-1",recipient="УК Тест",meters=listOf(Meter(id="m1",name="Холодная вода",unit="м³",kind="cold_water",serial="001",readings=listOf(Reading(id="r1",value=12.5,valueText="00012.500",timestamp=1L,zoneValues=mapOf("TOTAL" to "00012.500"))),status="active"))))
    private val multi = listOf(Address(id="a2",name="Квартира",meters=listOf(Meter(id="m2",name="Электричество",unit="кВт·ч",kind="electricity",tariffZones=listOf("T1","T2","T3"),tariffSchedule=listOf(TariffScheduleEntry("T1",100L,"6.25"),TariffScheduleEntry("T2",100L,"3.10"),TariffScheduleEntry("T3",100L,"2.20")),readings=listOf(Reading(id="r2",value=123.0,valueText="123",timestamp=200L,zoneValues=linkedMapOf("T1" to "00123.0","T2" to "00045.0","T3" to "00009.0")))))))

    @Test fun csvKeepsRussianAndDecimalText() {
        val csv=MeterTransfer.csv(sample)
        assertTrue(csv.startsWith("\uFEFF"))
        assertTrue(csv.contains("\"Дом\""))
        assertTrue(csv.contains("\"00012.500\""))
        assertTrue(csv.contains("\"УК Тест\""))
    }

    @Test fun csvWritesEachTariffZoneWithoutTotalFabrication() {
        val csv=MeterTransfer.csv(multi)
        assertTrue(csv.contains("\"T1\";\"00123.0\""))
        assertTrue(csv.contains("\"T2\";\"00045.0\""))
        assertTrue(csv.contains("\"T3\";\"00009.0\""))
        assertFalse(csv.contains("\"TOTAL\";\"00123.0\""))
    }

    @Test fun transmissionIsExplicitlyManual() {
        val text=MeterTransfer.transmissionText(sample.first())
        assertTrue(text.contains("00012.500 м³"))
        assertTrue(text.contains("Лицевой счёт: ЛС-1"))
        assertTrue(text.contains("Передача в УК/РСО выполняется пользователем"))
    }

    @Test fun transmissionShowsAllTariffZones() {
        val text=MeterTransfer.transmissionText(multi.first())
        assertTrue(text.contains("T1 00123.0"))
        assertTrue(text.contains("T2 00045.0"))
        assertTrue(text.contains("T3 00009.0"))
    }

    @Test fun v1BackupStillParsesAsTotal() {
        val payload=MeterTransfer.parseBackup(zip(manifestV1()))
        val reading=payload.addresses.single().meters.single().readings.single()
        assertEquals("00012.500",reading.valueText)
        assertEquals(mapOf("TOTAL" to "00012.500"),reading.zoneValues)
        assertTrue(payload.submissions.isEmpty())
    }

    @Test fun v2BackupKeepsZonesAndSchedule() {
        val payload=MeterTransfer.parseBackup(zip(manifestV2()))
        val meter=payload.addresses.single().meters.single()
        assertEquals(listOf("T1","T2","T3"),meter.tariffZones)
        assertEquals("6.25",meter.tariffSchedule.first().priceText)
        assertEquals("00045.0",meter.readings.single().zoneValues["T2"])
    }

    @Test fun v4BackupKeepsMeteringPointPeriodAndSubmissionSnapshot() {
        val address = Address(
            id="a4", name="Дом", meters=listOf(
                Meter(
                    id="m4", name="Холодная вода", unit="м³", kind="cold_water", meteringPointId="p4",
                    readings=listOf(Reading(id="r4",value=12.0,valueText="00012.000",timestamp=4L,zoneValues=mapOf("TOTAL" to "00012.000"),billingPeriod="2026-09"))
                )
            )
        )
        val submission = Submission(
            id="s4", addressId="a4", billingPeriod="2026-09", submittedAt=5L, recipient="УК",
            snapshotText="ХВС: 00012.000", items=listOf(SubmissionItem("p4","m4","TOTAL","00012.000"))
        )
        val payload = MeterTransfer.parseBackup(MeterTransfer.createBackupBytes(listOf(address), listOf(submission)))
        assertEquals("p4", payload.addresses.single().meters.single().meteringPointId)
        assertEquals("2026-09", payload.addresses.single().meters.single().readings.single().billingPeriod)
        assertEquals("ХВС: 00012.000", payload.submissions.single().snapshotText)
        assertEquals("p4", payload.submissions.single().items.single().meteringPointId)
    }

    @Test fun v4ChecksumMismatchIsRejectedBeforeRestore() {
        val address = Address(id="a4",name="Дом",meters=listOf(Meter(id="m4",name="Газ",unit="м³",meteringPointId="p4",readings=listOf(Reading(id="r4",value=1.0,valueText="1",zoneValues=mapOf("TOTAL" to "1"),billingPeriod="2026-09")))))
        val valid = MeterTransfer.createBackupBytes(listOf(address))
        val tampered = rewriteZipEntry(valid,"data.json") { bytes -> bytes + " ".toByteArray() }
        assertTrue(runCatching { MeterTransfer.parseBackup(tampered) }.isFailure)
    }

    @Test fun v4RejectsSubmissionThatPointsToAnotherAddress() {
        val addresses = listOf(
            Address(id="a1",name="A",meters=listOf(Meter(id="m1",name="Вода",unit="м³",meteringPointId="p1"))),
            Address(id="a2",name="B",meters=listOf(Meter(id="m2",name="Газ",unit="м³",meteringPointId="p2")))
        )
        val bad = Submission(id="s1",addressId="a1",billingPeriod="2026-09",items=listOf(SubmissionItem("p2","m2","TOTAL","1")))
        assertTrue(runCatching { MeterTransfer.createBackupBytes(addresses,listOf(bad)) }.isFailure)
    }

    @Test fun damagedBackupIsRejectedBeforeRestore() {
        val result=runCatching{MeterTransfer.parseBackup("not a zip".toByteArray())}
        assertTrue(result.isFailure)
    }

    @Test fun unsupportedVersionIsRejected() {
        val result=runCatching{MeterTransfer.parseBackup(zip(manifestV1().replace("\"version\":1","\"version\":99")))}
        assertTrue(result.isFailure)
    }

    @Test fun manifestPhotoMissingFromArchiveIsRejected() {
        val incomplete=manifestV1().replace("\"hasPhoto\":false","\"hasPhoto\":true")
        val result=runCatching{MeterTransfer.parseBackup(zip(incomplete))}
        assertTrue(result.isFailure)
    }

    @Test fun unexpectedPhotoIsRejected() {
        val result=runCatching{MeterTransfer.parseBackup(zip(manifestV1(), mapOf("unknown" to byteArrayOf(1,2,3))))}
        assertTrue(result.isFailure)
    }

    private fun manifestV1()="""{"format":"moi-schetschiki-backup","version":1,"addresses":[{"id":"a1","name":"Дом","account":"ЛС-1","recipient":"УК Тест","meters":[{"id":"m1","name":"Холодная вода","unit":"м³","kind":"cold_water","serial":"001","integerDigits":5,"fractionDigits":3,"status":"active","readings":[{"id":"r1","valueText":"00012.500","timestamp":1,"note":"","rollover":false,"hasPhoto":false}]}]}]}"""
    private fun manifestV2()="""{"format":"moi-schetschiki-backup","version":2,"addresses":[{"id":"a2","name":"Квартира","meters":[{"id":"m2","name":"Электричество","unit":"кВт·ч","kind":"electricity","serial":"","tariffZones":["T1","T2","T3"],"tariffSchedule":[{"zone":"T1","validFrom":100,"priceText":"6.25"}],"readings":[{"id":"r2","valueText":"00123.0","zoneValues":{"T1":"00123.0","T2":"00045.0","T3":"00009.0"},"timestamp":200,"note":"","rollover":false,"hasPhoto":false}]}]}]}"""
    private fun zip(json:String,photos:Map<String,ByteArray> = emptyMap()):ByteArray{val out=ByteArrayOutputStream();ZipOutputStream(out).use{z->z.putNextEntry(ZipEntry("manifest.json"));z.write(json.toByteArray());z.closeEntry();photos.forEach{(id,bytes)->z.putNextEntry(ZipEntry("photos/$id.jpg"));z.write(bytes);z.closeEntry()}};return out.toByteArray()}

    private fun rewriteZipEntry(input:ByteArray,target:String,transform:(ByteArray)->ByteArray):ByteArray {
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(input)).use { z ->
            while(true){ val e=z.nextEntry?:break; entries[e.name]=z.readBytes(); z.closeEntry() }
        }
        val out=ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (name,bytes) -> z.putNextEntry(ZipEntry(name)); z.write(if(name==target)transform(bytes)else bytes); z.closeEntry() } }
        return out.toByteArray()
    }
}
