package ru.egor.meters

import java.time.YearMonth

/**
 * Computes period completion across transmission templates without mixing recipients.
 * A period is complete only when every configured template is complete.
 */
object SubmissionStatusPolicy {
    fun forTemplates(
        repo: MeterRepository,
        address: Address,
        templates: List<TransmissionTemplate>,
        period: YearMonth
    ): SubmissionStatus {
        val statuses = templates.map { template ->
            SubmissionWorkflow.statusForTemplate(repo, address, template, period).status
        }
        return aggregate(statuses)
    }

    fun aggregate(statuses: List<SubmissionStatus>): SubmissionStatus = when {
        statuses.isEmpty() -> SubmissionStatus.NONE
        statuses.all { it == SubmissionStatus.COMPLETE } -> SubmissionStatus.COMPLETE
        statuses.any { it != SubmissionStatus.NONE } -> SubmissionStatus.PARTIAL
        else -> SubmissionStatus.NONE
    }
}
