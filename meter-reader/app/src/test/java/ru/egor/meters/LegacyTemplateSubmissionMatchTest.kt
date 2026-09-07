package ru.egor.meters

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTemplateSubmissionMatchTest {
    @Test
    fun legacySubmissionDoesNotClaimSpecificTemplateEvenWhenRecipientMatches() {
        val legacy = Submission(
            id = "legacy-random-submission-id",
            addressId = "a1",
            billingPeriod = "2026-08",
            recipient = "УК Дом",
            items = listOf(SubmissionItem("p1", "m1", "TOTAL", "10"))
        )
        val template = TransmissionTemplate(
            id = "template-uk",
            addressId = "a1",
            name = "УК",
            recipient = "УК Дом",
            meteringPointIds = setOf("p1")
        )

        assertFalse(SubmissionWorkflow.submissionMatchesTemplate(legacy, template))
    }

    @Test
    fun newSubmissionMatchesOnlyItsEncodedTemplateIdentity() {
        val sourceTemplate = TransmissionTemplate(
            id = "template-a",
            addressId = "a1",
            name = "Первый",
            recipient = "УК Дом",
            meteringPointIds = setOf("p1")
        )
        val otherTemplate = sourceTemplate.copy(id = "template-b", name = "Второй")
        val address = Address(
            id = "a1",
            name = "Дом",
            meters = listOf(
                Meter(
                    id = "m1",
                    meteringPointId = "p1",
                    name = "Вода",
                    unit = "м³",
                    readings = listOf(
                        Reading(
                            value = 10.0,
                            valueText = "10",
                            zoneValues = mapOf("TOTAL" to "10"),
                            billingPeriod = "2026-09"
                        )
                    )
                )
            )
        )
        val submission = SubmissionWorkflow.createSubmission(
            address,
            sourceTemplate,
            java.time.YearMonth.of(2026, 9)
        )

        assertTrue(SubmissionWorkflow.submissionMatchesTemplate(submission, sourceTemplate))
        assertFalse(SubmissionWorkflow.submissionMatchesTemplate(submission, otherTemplate))
    }

    @Test
    fun unnamedLegacySubmissionAlsoFailsClosed() {
        val legacy = Submission(
            id = "legacy-random-submission-id",
            addressId = "a1",
            billingPeriod = "2026-08",
            recipient = "",
            items = listOf(SubmissionItem("p1", "m1", "TOTAL", "10"))
        )
        val template = TransmissionTemplate(
            id = "template-unnamed",
            addressId = "a1",
            name = "Все показания",
            recipient = "",
            meteringPointIds = setOf("p1")
        )

        assertFalse(SubmissionWorkflow.submissionMatchesTemplate(legacy, template))
    }
}
