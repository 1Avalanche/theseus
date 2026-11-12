package theseus.core.adapters.db

import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import theseus.core.application.UsageRepository
import theseus.core.domain.user.Usage
import java.time.Instant

class ExposedUsageRepository(
    private val db: CoreDatabase,
) : UsageRepository {
    override suspend fun recordUsage(usage: Usage): Long =
        db.tx {
            UsageTable.insert {
                it[userId] = usage.userId
                it[role] = usage.role.uppercase()
                it[model] = usage.model
                it[generationId] = usage.generationId
                it[tokensIn] = usage.tokensIn
                it[tokensOut] = usage.tokensOut
                it[tokensCached] = usage.tokensCached
                it[costUsd] = usage.costUsd
                it[latencyMs] = usage.latencyMs
                it[createdAt] = usage.createdAt.toUtc()
            }[UsageTable.id]
        }

    override suspend fun usage(
        from: Instant,
        to: Instant,
    ): List<Usage> =
        db.tx {
            UsageTable.selectAll().where { (UsageTable.createdAt greaterEq from.toUtc()) and (UsageTable.createdAt less to.toUtc()) }.map {
                Usage(
                    it[UsageTable.userId],
                    it[UsageTable.role].lowercase(),
                    it[UsageTable.model],
                    it[UsageTable.generationId],
                    it[UsageTable.tokensIn],
                    it[UsageTable.tokensOut],
                    it[UsageTable.tokensCached],
                    it[UsageTable.costUsd],
                    it[UsageTable.latencyMs],
                    it[UsageTable.createdAt].toInstant(),
                )
            }
        }
}
