package theseus.core.adapters.db

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import theseus.core.application.InviteRepository
import theseus.core.domain.user.Gender
import theseus.core.domain.user.Invite
import java.time.Instant

class ExposedInviteRepository(
    private val db: CoreDatabase,
) : InviteRepository {
    override suspend fun createInvite(
        hash: String,
        gender: Gender,
        label: String?,
        now: Instant,
        expires: Instant,
    ): Invite =
        db.tx {
            val id =
                InvitesTable.insert {
                    it[codeHash] = hash
                    it[InvitesTable.gender] = gender.name
                    it[InvitesTable.label] = label
                    it[createdAt] = now.toUtc()
                    it[expiresAt] = expires.toUtc()
                }[InvitesTable.id]
            InvitesTable
                .selectAll()
                .where { InvitesTable.id eq id }
                .single()
                .toInvite()
        }

    override suspend fun lockInvite(hash: String): Invite? =
        db.tx {
            InvitesTable
                .selectAll()
                .where { InvitesTable.codeHash eq hash }
                .forUpdate()
                .singleOrNull()
                ?.toInvite()
        }

    override suspend fun lockInvite(id: Long): Invite? =
        db.tx {
            InvitesTable
                .selectAll()
                .where { InvitesTable.id eq id }
                .forUpdate()
                .singleOrNull()
                ?.toInvite()
        }

    override suspend fun saveInvite(invite: Invite) =
        db.tx {
            InvitesTable.update({ InvitesTable.id eq invite.id }) {
                it[usedAt] = invite.usedAt?.toUtc()
                it[usedByUserId] = invite.usedByUserId
                it[revokedAt] = invite.revokedAt?.toUtc()
            }
            Unit
        }

    override suspend fun listInvites(): List<Invite> =
        db.tx { InvitesTable.selectAll().orderBy(InvitesTable.createdAt).map { it.toInvite() } }

    private fun ResultRow.toInvite() =
        Invite(
            this[InvitesTable.id],
            this[InvitesTable.codeHash],
            Gender.valueOf(this[InvitesTable.gender]),
            this[InvitesTable.label],
            this[InvitesTable.createdAt].toInstant(),
            this[InvitesTable.expiresAt].toInstant(),
            this[InvitesTable.usedAt]?.toInstant(),
            this[InvitesTable.usedByUserId],
            this[InvitesTable.revokedAt]?.toInstant(),
        )
}
