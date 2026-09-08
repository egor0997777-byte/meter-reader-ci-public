package ru.egor.meters

import org.junit.Assert.assertEquals
import org.junit.Test

class SubmissionStatusPolicyTest {
    @Test
    fun allTemplatesMustBeCompleteBeforePeriodIsComplete() {
        assertEquals(
            SubmissionStatus.PARTIAL,
            SubmissionStatusPolicy.aggregate(listOf(SubmissionStatus.COMPLETE, SubmissionStatus.NONE))
        )
        assertEquals(
            SubmissionStatus.PARTIAL,
            SubmissionStatusPolicy.aggregate(listOf(SubmissionStatus.COMPLETE, SubmissionStatus.PARTIAL))
        )
        assertEquals(
            SubmissionStatus.COMPLETE,
            SubmissionStatusPolicy.aggregate(listOf(SubmissionStatus.COMPLETE, SubmissionStatus.COMPLETE))
        )
    }

    @Test
    fun noSubmittedTemplateIsNone() {
        assertEquals(
            SubmissionStatus.NONE,
            SubmissionStatusPolicy.aggregate(listOf(SubmissionStatus.NONE, SubmissionStatus.NONE))
        )
        assertEquals(SubmissionStatus.NONE, SubmissionStatusPolicy.aggregate(emptyList()))
    }
}
