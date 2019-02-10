package org.ostelco.prime.storage.graph

import arrow.core.Either
import arrow.core.flatMap
import com.fasterxml.jackson.core.type.TypeReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.v1.AccessMode.READ
import org.neo4j.driver.v1.AccessMode.WRITE
import org.neo4j.driver.v1.StatementResult
import org.neo4j.driver.v1.Transaction
import org.ostelco.prime.getLogger
import org.ostelco.prime.jsonmapper.asJson
import org.ostelco.prime.jsonmapper.objectMapper
import org.ostelco.prime.model.HasId
import org.ostelco.prime.storage.AlreadyExistsError
import org.ostelco.prime.storage.NotCreatedError
import org.ostelco.prime.storage.NotDeletedError
import org.ostelco.prime.storage.NotFoundError
import org.ostelco.prime.storage.NotUpdatedError
import org.ostelco.prime.storage.StoreError
import org.ostelco.prime.storage.graph.Graph.read
import org.ostelco.prime.storage.graph.Graph.write
import org.ostelco.prime.storage.graph.ObjectHandler.getProperties

//
// Schema classes
//

data class EntityType<ENTITY : HasId>(
        private val dataClass: Class<ENTITY>,
        val name: String = dataClass.simpleName) {

    var entityStore: EntityStore<ENTITY>? = null

    fun createEntity(map: Map<String, Any>): ENTITY = ObjectHandler.getObject(map, dataClass)
}

data class RelationType<FROM : HasId, RELATION, TO : HasId>(
        val relation: Relation,
        val from: EntityType<FROM>,
        val to: EntityType<TO>,
        private val dataClass: Class<RELATION>) {

    fun createRelation(map: Map<String, Any>): RELATION? {
        return ObjectHandler.getObject(map, dataClass)
    }
}

class EntityStore<E : HasId>(private val entityType: EntityType<E>) {

    init {
        entityType.entityStore = this
    }

    fun get(id: String, transaction: Transaction): Either<StoreError, E> {
        return read("""MATCH (node:${entityType.name} {id: '$id'}) RETURN node;""", transaction) {
            if (it.hasNext())
                Either.right(entityType.createEntity(it.single().get("node").asMap()))
            else
                Either.left(NotFoundError(type = entityType.name, id = id))
        }
    }

    fun create(entity: E, transaction: Transaction): Either<StoreError, Unit> {

        if (get(entity.id, transaction).isRight()) {
            return Either.left(AlreadyExistsError(type = entityType.name, id = entity.id))
        }

        val properties = getProperties(entity)
        val strProps: String = properties.entries.joinToString(separator = ",") { """ `${it.key}`: "${it.value}"""" }
                .let { if (it.isNotBlank()) ",$it" else it }
        return write("""CREATE (node:${entityType.name} { id:"${entity.id}"$strProps });""",
                transaction) {
            if (it.summary().counters().nodesCreated() == 1)
                Either.right(Unit)
            else
                Either.left(NotCreatedError(type = entityType.name, id = entity.id))
        }
    }

    fun create(id: String, transaction: Transaction): Either<StoreError, Unit> {

        if (get(id, transaction).isRight()) {
            return Either.left(AlreadyExistsError(type = entityType.name, id = id))
        }

        return write("""CREATE (node:${entityType.name} { id:"$id"});""",
                transaction) {
            if (it.summary().counters().nodesCreated() == 1)
                Either.right(Unit)
            else
                Either.left(NotCreatedError(type = entityType.name, id = id))
        }
    }

    fun <TO : HasId> getRelated(
            id: String,
            relationType: RelationType<E, *, TO>,
            transaction: Transaction): Either<StoreError, List<TO>> {

        return exists(id, transaction).flatMap { _ ->

            read("""
                MATCH (:${relationType.from.name} {id: '$id'})-[:${relationType.relation.name}]->(node:${relationType.to.name})
                RETURN node;
                """.trimIndent(),
                    transaction) { statementResult ->
                Either.right(
                        statementResult.list { relationType.to.createEntity(it["node"].asMap()) })
            }
        }
    }

    fun <FROM : HasId> getRelatedFrom(
            id: String,
            relationType: RelationType<FROM, *, E>,
            transaction: Transaction): Either<StoreError, List<FROM>> {

        return exists(id, transaction).flatMap { _ ->

            read("""
                MATCH (node:${relationType.from.name})-[:${relationType.relation.name}]->(:${relationType.to.name} {id: '$id'})
                RETURN node;
                """.trimIndent(),
                    transaction) { statementResult ->
                Either.right(
                        statementResult.list { relationType.from.createEntity(it["node"].asMap()) })
            }
        }
    }

    fun <RELATION : Any> getRelations(
            id: String,
            relationType: RelationType<E, RELATION, *>,
            transaction: Transaction): Either<StoreError, List<RELATION>> {

        return exists(id, transaction).flatMap { _ ->
            read("""
                MATCH (from:${entityType.name} { id: '$id' })-[r:${relationType.relation.name}]-()
                return r;
                """.trimIndent(),
                    transaction) { statementResult ->
                Either.right(
                        statementResult.list { relationType.createRelation(it["r"].asMap()) }
                                .filterNotNull())
            }
        }
    }

    fun update(entity: E, transaction: Transaction): Either<StoreError, Unit> {

        return exists(entity.id, transaction).flatMap {
            val properties = getProperties(entity)
            val setClause: String = properties.entries.fold("") { acc, entry -> """$acc SET node.`${entry.key}` = '${entry.value}' """ }
            write("""MATCH (node:${entityType.name} { id: '${entity.id}' }) $setClause ;""",
                    transaction) {
                Either.cond(
                        test = it.summary().counters().containsUpdates(), // TODO vihang: this is not perfect way to check if updates are applied
                        ifTrue = {},
                        ifFalse = { NotUpdatedError(type = entityType.name, id = entity.id) })
            }
        }
    }

    fun delete(id: String, transaction: Transaction): Either<StoreError, Unit> =
            exists(id, transaction).flatMap {
                write("""MATCH (node:${entityType.name} {id: '$id'} ) DETACH DELETE node;""",
                        transaction) {
                    Either.cond(
                            test = it.summary().counters().nodesDeleted() == 1,
                            ifTrue = {},
                            ifFalse = { NotDeletedError(type = entityType.name, id = id) })
                }
            }

    fun exists(id: String, transaction: Transaction): Either<StoreError, Unit> =
            read("""MATCH (node:${entityType.name} {id: '$id'} ) RETURN count(node);""",
                    transaction) { statementResult ->
                Either.cond(
                        test = statementResult.single()["count(node)"].asInt(0) == 1,
                        ifTrue = {},
                        ifFalse = { NotFoundError(type = entityType.name, id = id) })
            }

    fun doNotExist(id: String, transaction: Transaction): Either<StoreError, Unit> =
            read("""MATCH (node:${entityType.name} {id: '$id'} ) RETURN count(node);""",
                    transaction) { statementResult ->
                Either.cond(
                        test = statementResult.single()["count(node)"].asInt(1) == 0,
                        ifTrue = {},
                        ifFalse = { AlreadyExistsError(type = entityType.name, id = id) })
            }
}

// TODO vihang: check if relation already exists, with allow duplicate boolean flag param
class RelationStore<FROM : HasId, TO : HasId>(private val relationType: RelationType<FROM, *, TO>) {

    fun create(from: FROM, relation: Any, to: TO, transaction: Transaction): Either<StoreError, Unit> {

        val properties = getProperties(relation)
        val strProps: String = properties.entries.joinToString(",") { """`${it.key}`: "${it.value}"""" }
        return write("""
                MATCH (from:${relationType.from.name} { id: '${from.id}' }),(to:${relationType.to.name} { id: '${to.id}' })
                CREATE (from)-[:${relationType.relation.name} { $strProps } ]->(to);
                """.trimIndent(),
                transaction) {
            // TODO vihang: validate if 'from' and 'to' node exists
            Either.cond(
                    test = it.summary().counters().relationshipsCreated() == 1,
                    ifTrue = {},
                    ifFalse = { NotCreatedError(type = relationType.relation.name, id = "${from.id} -> ${to.id}") })
        }
    }

    fun create(from: FROM, to: TO, transaction: Transaction): Either<StoreError, Unit> = write("""
                MATCH (from:${relationType.from.name} { id: '${from.id}' }),(to:${relationType.to.name} { id: '${to.id}' })
                CREATE (from)-[:${relationType.relation.name}]->(to);
                """.trimIndent(),
            transaction) {
        // TODO vihang: validate if 'from' and 'to' node exists
        Either.cond(
                test = it.summary().counters().relationshipsCreated() == 1,
                ifTrue = {},
                ifFalse = { NotCreatedError(type = relationType.relation.name, id = "${from.id} -> ${to.id}") })
    }

    fun create(fromId: String, toId: String, transaction: Transaction): Either<StoreError, Unit> = write("""
                MATCH (from:${relationType.from.name} { id: '$fromId' }),(to:${relationType.to.name} { id: '$toId' })
                CREATE (from)-[:${relationType.relation.name}]->(to);
                """.trimIndent(),
            transaction) {
        // TODO vihang: validate if 'from' and 'to' node exists
        Either.cond(
                test = it.summary().counters().relationshipsCreated() == 1,
                ifTrue = {},
                ifFalse = { NotCreatedError(type = relationType.relation.name, id = "$fromId -> $toId") })
    }

    fun create(fromId: String, relation: Any, toId: String, transaction: Transaction): Either<StoreError, Unit> = write("""
                MATCH (from:${relationType.from.name} { id: '$fromId' }),(to:${relationType.to.name} { id: '$toId' })
                CREATE (from)-[:${relationType.relation.name}]->(to);
                """.trimIndent(),
            transaction) {
        // TODO vihang: validate if 'from' and 'to' node exists
        Either.cond(
                test = it.summary().counters().relationshipsCreated() == 1,
                ifTrue = {},
                ifFalse = { NotCreatedError(type = relationType.relation.name, id = "$fromId -> $toId") })
    }

    fun create(fromId: String, toIds: Collection<String>, transaction: Transaction): Either<StoreError, Unit> = write("""
                MATCH (to:${relationType.to.name})
                WHERE to.id in [${toIds.joinToString(",") { "'$it'" }}]
                WITH to
                MATCH (from:${relationType.from.name} { id: '$fromId' })
                CREATE (from)-[:${relationType.relation.name}]->(to);
                """.trimIndent(),
            transaction) {
        // TODO vihang: validate if 'from' and 'to' node exists
        val actualCount = it.summary().counters().relationshipsCreated()
        Either.cond(
                test = actualCount == toIds.size,
                ifTrue = {},
                ifFalse = {
                    NotCreatedError(
                            type = relationType.relation.name,
                            expectedCount = toIds.size,
                            actualCount = actualCount)
                })
    }

    fun create(fromIds: Collection<String>, toId: String, transaction: Transaction): Either<StoreError, Unit> = write("""
                MATCH (from:${relationType.from.name})
                WHERE from.id in [${fromIds.joinToString(",") { "'$it'" }}]
                WITH from
                MATCH (to:${relationType.to.name} { id: '$toId' })
                CREATE (from)-[:${relationType.relation.name}]->(to);
                """.trimIndent(),
            transaction) {
        // TODO vihang: validate if 'from' and 'to' node exists
        val actualCount = it.summary().counters().relationshipsCreated()
        Either.cond(
                test = actualCount == fromIds.size,
                ifTrue = {},
                ifFalse = {
                    NotCreatedError(
                            type = relationType.relation.name,
                            expectedCount = fromIds.size,
                            actualCount = actualCount)
                })
    }

    fun removeAll(toId: String, transaction: Transaction): Either<StoreError, Unit> = write("""
                MATCH (from:${relationType.from.name})-[r:${relationType.relation.name}]->(to:${relationType.to.name} { id: '$toId' })
                DELETE r;
        """.trimIndent(),
            transaction) {
        // TODO vihang: validate if 'to' node exists
        Either.right(Unit)
    }
}

class ChangeableRelationStore<FROM : HasId, TO : HasId, RELATION : HasId>(private val relationType: RelationType<FROM, RELATION, TO>) {

    fun get(id: String, transaction: Transaction): Either<StoreError, RELATION> {
        return read("""MATCH (from)-[r:${relationType.relation.name}{id:'$id'}]->(to) RETURN r;""", transaction) {
            if (it.hasNext()) {
                Either.right(relationType.createRelation(it.single().get("r").asMap())!!)
            } else {
                Either.left(NotFoundError(type = relationType.relation.name, id = id))
            }
        }
    }

    fun update(relation: RELATION, transaction: Transaction): Either<StoreError, Unit> {
        val properties = getProperties(relation)
        val setClause: String = properties.entries.fold("") { acc, entry -> """$acc SET r.`${entry.key}` = "${entry.value}" """ }
        return write("""MATCH (from)-[r:${relationType.relation.name}{id:'${relation.id}'}]->(to) $setClause ;""", transaction) {
            Either.cond(
                    test = it.summary().counters().containsUpdates(), // TODO vihang: this is not perfect way to check if updates are applied
                    ifTrue = {},
                    ifFalse = { NotUpdatedError(type = relationType.relation.name, id = relation.id) })
        }
    }
}

// Removes double apostrophes from key values in a JSON string.
// Usage: output = re.replace(input, "$1$2$3")
val re = Regex("""([,{])\s*"([^"]+)"\s*(:)""")

class UniqueRelationStore<FROM : HasId, TO : HasId>(private val relationType: RelationType<FROM, *, TO>) {

    fun get(from: String, transaction: Transaction): Either<StoreError, List<TO>> {
        return read("""MATCH (from:${relationType.from.name} {id: '${from}'})-[r:${relationType.relation.name}]->(to:${relationType.to.name})
                RETURN to""".trimMargin(), transaction) {
            Either.cond(it.hasNext(),
                    ifTrue = { it.list { relationType.to.createEntity(it["to"].asMap()) }.filterNotNull() },
                    ifFalse = { NotFoundError(relationType.relation.name, from) })
        }
    }

    fun getFrom(to: String, transaction: Transaction): Either<StoreError, List<FROM>> {
        return read("""MATCH (from:${relationType.from.name})-[r:${relationType.relation.name}]->(to:${relationType.to.name} {id: '${to}'})
                RETURN from""".trimMargin(), transaction) {
            Either.cond(it.hasNext(),
                    ifTrue = { it.list { relationType.from.createEntity(it["from"].asMap()) }.filterNotNull() },
                    ifFalse = { NotFoundError(relationType.relation.name, to) })
        }
    }

    fun create(from: String, to: String, transaction: Transaction): Either<StoreError, Unit> {
        return write("""MATCH (from:${relationType.from.name} {id: '${from}'}),(to:${relationType.to.name} {id: '${to}'})
                MERGE (from)-[:${relationType.relation.name}]->(to)""".trimMargin(), transaction) {
            Either.cond(it.summary().counters().relationshipsCreated() == 1,
                    ifTrue = { Unit },
                    ifFalse = { NotCreatedError(relationType.relation.name) })
        }
    }

    fun delete(from: String, to: String, transaction: Transaction): Either<StoreError, Unit> {
        return write("""MATCH (from:${relationType.from.name} { id: '$from'})-[r:${relationType.relation.name}]->(to:${relationType.to.name} {id: '${to}'})
                DELETE r""".trimMargin(), transaction) {
            Either.cond(it.summary().counters().relationshipsDeleted() == 1,
                    ifTrue = { Unit },
                    ifFalse = { NotDeletedError(relationType.relation.name, "${from} -> ${to}") })
        }
    }

    fun getProperties(from: String, to: String, transaction: Transaction): Either<StoreError, Map<String, Any>> {
        return read("""MATCH (from:${relationType.from.name} {id: '${from}'})-[r:${relationType.relation.name}]->(to:${relationType.to.name} {id: '${to}'})
                RETURN r""".trimMargin(), transaction) {
            Either.cond(it.hasNext(),
                    ifTrue = { it.single()["r"].asMap() },
                    ifFalse = { NotFoundError(relationType.relation.name, "${from} -> ${to}") })
        }
    }

    fun setProperties(from: String, to: String, properties: Map<String, Any>, transaction: Transaction): Either<StoreError, Unit> {
        return write("""MATCH (from:${relationType.from.name} {id: '${from}'})-[r:${relationType.relation.name}]->(to:${relationType.to.name} {id: '${to}'})
                SET r = ${re.replace(asJson(properties), "$1$2$3")}""".trimMargin(), transaction) {
            Either.cond(it.summary().counters().propertiesSet() > 0,
                    ifTrue = { Unit },
                    ifFalse = { NotUpdatedError(relationType.relation.name, "${from} -> ${to}") })
        }
    }
}

//
// Helper wrapping Neo4j Client
//
object Graph {

    private val LOG by getLogger()

    fun <R> write(query: String, transaction: Transaction, transform: (StatementResult) -> R): R {
        LOG.trace("write:[\n$query\n]")
        return transaction.run(query).let(transform)
    }

    fun <R> read(query: String, transaction: Transaction, transform: (StatementResult) -> R): R {
        LOG.trace("read:[\n$query\n]")
        return transaction.run(query).let(transform)
    }

    suspend fun <R> writeSuspended(query: String, transaction: Transaction, transform: (StatementResult) -> R): R {
        LOG.trace("write:[\n$query\n]")
        return withContext(Dispatchers.Default) {
            transaction.run(query)
        }.let(transform)
    }
}

fun <R> readTransaction(action: ReadTransaction.() -> R): R =
        Neo4jClient.driver.session(READ)
                .use { session ->
                    session.readTransaction {
                        action(ReadTransaction(PrimeTransaction(it)))
                    }
                }

fun <R> writeTransaction(action: WriteTransaction.() -> R): R =
        Neo4jClient.driver.session(WRITE)
                .use { session ->
                    session.writeTransaction {
                        action(WriteTransaction(PrimeTransaction(it)))
                    }
                }

suspend fun <R> suspendedWriteTransaction(action: suspend WriteTransaction.() -> R): R =
        Neo4jClient.driver.session(WRITE)
                .use { session ->
                    val transaction = PrimeTransaction(session.beginTransaction())
                    val result = action(WriteTransaction(transaction))
                    transaction.success()
                    result
                }

data class ReadTransaction(val transaction: PrimeTransaction)
data class WriteTransaction(val transaction: PrimeTransaction)

//
// Object mapping functions
//
object ObjectHandler {

    private const val SEPARATOR = '/'

    //
    // Object to Map
    //

    fun getProperties(any: Any): Map<String, Any> = toSimpleMap(
            objectMapper.convertValue(any, object : TypeReference<Map<String, Any?>>() {}))

    private fun toSimpleMap(map: Map<String, Any?>, prefix: String = ""): Map<String, Any> {
        val outputMap: MutableMap<String, Any> = LinkedHashMap()
        map.forEach { key, value ->
            when (value) {
                is Map<*, *> -> outputMap.putAll(toSimpleMap(value as Map<String, Any>, "$prefix$key$SEPARATOR"))
                is List<*> -> println("Skipping list value: $value for key: $key")
                null -> Unit
                else -> outputMap["$prefix$key"] = value
            }
        }
        return outputMap
    }

    //
    // Map to Object
    //

    fun <D> getObject(map: Map<String, Any>, dataClass: Class<D>): D {
        return objectMapper.convertValue(toNestedMap(map), dataClass)
    }

    internal fun toNestedMap(map: Map<String, Any>): Map<String, Any> {
        val outputMap: MutableMap<String, Any> = LinkedHashMap()
        map.forEach { key, value ->
            if (key.contains(SEPARATOR)) {
                val keys = key.split(SEPARATOR)
                var loopMap = outputMap
                repeat(keys.size - 1) { i ->
                    loopMap.putIfAbsent(keys[i], LinkedHashMap<String, Any>())
                    loopMap = loopMap[keys[i]] as MutableMap<String, Any>
                }
                loopMap[keys.last()] = value

            } else {
                outputMap[key] = value
            }
        }
        return outputMap
    }
}