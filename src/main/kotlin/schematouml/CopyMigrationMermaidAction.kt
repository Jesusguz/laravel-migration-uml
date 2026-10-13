package schematouml

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.StringSelection

class CopyMigrationMermaidAction : AnAction() {

    data class ColumnInfo(
        val name: String,
        val type: String,
        val metadata: MutableList<String> = mutableListOf()
    )

    data class RelationInfo(
        val fromTable: String,
        val toTable: String,
        val foreignKey: String
    )

    data class TableSchema(
        val name: String,
        val columns: MutableList<ColumnInfo> = mutableListOf(),
        val relations: MutableList<RelationInfo> = mutableListOf()
    )

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }

        val isValid = files?.any { it.isDirectory || it.extension == "php" } == true
        e.presentation.isEnabledAndVisible = isValid
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
            ?: e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }
            ?: return

        val phpFiles = mutableListOf<VirtualFile>()
        for (file in files) {
            collectPhpFiles(file, phpFiles)
        }

        if (phpFiles.isEmpty()) return

        val tables = mutableListOf<TableSchema>()
        for (phpFile in phpFiles) {
            val content = String(phpFile.contentsToByteArray(), Charsets.UTF_8)
            parseMigration(content)?.let { tables.add(it) }
        }

        if (tables.isEmpty()) {
            notify(project, "No se encontraron definiciones Schema::create válidas.", NotificationType.WARNING)
            return
        }

        inferRelations(tables)

        val mermaidOutput = buildMermaidDiagram(tables)
        CopyPasteManager.getInstance().setContents(StringSelection(mermaidOutput))

        val countMsg = if (tables.size == 1) "1 tabla copiada" else "${tables.size} tablas copiadas"
        notify(project, "¡Listo! $countMsg con relaciones al portapapeles.", NotificationType.INFORMATION)
    }

    private fun collectPhpFiles(file: VirtualFile, list: MutableList<VirtualFile>) {
        if (file.isDirectory) {
            for (child in file.children) {
                collectPhpFiles(child, list)
            }
        } else if (file.extension == "php") {
            list.add(file)
        }
    }

    private fun parseMigration(code: String): TableSchema? {
        val tableRegex = Regex("""Schema::create\(\s*['"]([^'"]+)['"]""")
        val match = tableRegex.find(code) ?: return null
        val tableName = match.groupValues[1]

        val schema = TableSchema(name = tableName)

        val columnRegex = Regex("""[${'$'}]table->(\w+)\(([^)]*)\)(.*?);""", RegexOption.DOT_MATCHES_ALL)
        for (colMatch in columnRegex.findAll(code)) {
            val method = colMatch.groupValues[1]
            val rawArgs = colMatch.groupValues[2]
            val chain = colMatch.groupValues[3]

            val args = rawArgs.split(",").map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
            val colName = args.getOrNull(0) ?: ""

            when (method) {
                "id", "bigIncrements" -> schema.columns.add(ColumnInfo("id", "bigint", mutableListOf("PK")))
                "timestamps" -> {
                    schema.columns.add(ColumnInfo("created_at", "datetime"))
                    schema.columns.add(ColumnInfo("updated_at", "datetime"))
                }
                "softDeletes" -> schema.columns.add(ColumnInfo("deleted_at", "datetime", mutableListOf("null")))
                "unique", "index", "primary", "foreign" -> {}
                "foreignId" -> {
                    val column = ColumnInfo(colName, "bigint", mutableListOf("FK"))
                    extractModifiers(chain, column.metadata)
                    schema.columns.add(column)

                    if (chain.contains("constrained")) {
                        val targetTable = extractConstrainedTable(chain, colName)
                        schema.relations.add(RelationInfo(tableName, targetTable, colName))
                    }
                }
                else -> {
                    if (colName.isNotEmpty() && !colName.startsWith("[")) {
                        val lengthOrPrecision = args.getOrNull(1)
                        val mermaidType = mapColumnType(method, lengthOrPrecision)
                        val column = ColumnInfo(colName, mermaidType)
                        if (method == "string" && lengthOrPrecision != null) {
                            column.metadata.add("varchar($lengthOrPrecision)")
                        }
                        extractModifiers(chain, column.metadata)
                        schema.columns.add(column)
                    }
                }
            }
        }

        val explicitFkRegex = Regex(
            """[${'$'}]table\s*->\s*foreign\(\s*['"]([^'"]+)['"]\s*\)\s*->\s*references\([^)]+\)\s*->\s*on\(\s*['"]([^'"]+)['"]\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (fkMatch in explicitFkRegex.findAll(code)) {
            val fkCol = fkMatch.groupValues[1]
            val targetTable = fkMatch.groupValues[2]
            schema.relations.add(RelationInfo(tableName, targetTable, fkCol))
            schema.columns.find { it.name == fkCol }?.let {
                if (!it.metadata.contains("FK")) it.metadata.add(0, "FK")
            }
        }

        val compositeUniqueRegex = Regex("""[${'$'}]table->unique\(\s*\[([^\]]+)\]""")
        for (compositeMatch in compositeUniqueRegex.findAll(code)) {
            val cols = compositeMatch.groupValues[1].split(",")
                .map { it.trim().removeSurrounding("'").removeSurrounding("\"") }
            for (c in cols) {
                schema.columns.find { it.name == c }?.let {
                    if (!it.metadata.contains("UQ_compuesto")) it.metadata.add("UQ_compuesto")
                }
            }
        }

        return schema
    }

    private fun inferRelations(tables: List<TableSchema>) {
        val tableNames = tables.map { it.name }

        for (table in tables) {
            for (col in table.columns) {
                if (!col.name.endsWith("_id")) continue

                val alreadyHasRelation = table.relations.any { it.foreignKey == col.name }
                if (alreadyHasRelation) continue

                val rawBase = col.name.removeSuffix("_id").lowercase()
                val root = rawBase.trimEnd('a', 'e', 'o', 's')

                val targetTable = tableNames.firstOrNull { otherTable ->
                    if (otherTable == table.name) return@firstOrNull false
                    val otherLower = otherTable.lowercase()
                    otherLower == rawBase ||
                            otherLower == "${rawBase}s" ||
                            otherLower == "${rawBase}es" ||
                            (root.length >= 3 && otherLower.contains(root))
                }

                if (targetTable != null) {
                    table.relations.add(RelationInfo(table.name, targetTable, col.name))
                    if (!col.metadata.contains("FK")) {
                        col.metadata.add(0, "FK")
                    }
                }
            }
        }
    }

    private fun extractConstrainedTable(chain: String, colName: String): String {
        val constrainedRegex = Regex("""->constrained\(\s*['"]([^'"]+)['"]\s*\)""")
        val match = constrainedRegex.find(chain)
        return if (match != null) {
            match.groupValues[1]
        } else {
            if (colName.endsWith("_id")) {
                colName.removeSuffix("_id") + "s"
            } else {
                "related_table"
            }
        }
    }

    private fun extractModifiers(chain: String, metadata: MutableList<String>) {
        if (chain.contains("->index(") && !metadata.contains("index")) metadata.add("index")
        if (chain.contains("->unique(") && !metadata.contains("unique")) metadata.add("unique")
        if (chain.contains("->nullable(") && !metadata.contains("null")) metadata.add("null")

        val defaultRegex = Regex("""->default\(([^)]+)\)""")
        val defaultMatch = defaultRegex.find(chain)
        if (defaultMatch != null) {
            metadata.add("def: ${defaultMatch.groupValues[1].trim()}")
        }
    }

    private fun mapColumnType(type: String, secondaryArg: String?): String {
        return when (type) {
            "unsignedBigInteger", "bigInteger" -> "bigint"
            "integer", "unsignedInteger" -> "int"
            "tinyInteger" -> "tinyint"
            "string" -> "string"
            "text", "longText", "mediumText" -> "text"
            "dateTime", "timestamp" -> "datetime"
            "date" -> "date"
            "time" -> "time"
            "boolean" -> "boolean"
            "decimal", "float", "double" -> "decimal"
            "json", "jsonb" -> "json"
            else -> type
        }
    }

    private fun buildMermaidDiagram(tables: List<TableSchema>): String {
        val lines = mutableListOf("erDiagram")

        for (table in tables) {
            lines.add("    ${table.name} {")
            for (col in table.columns) {
                val isPk = col.metadata.contains("PK")
                val isFk = col.metadata.contains("FK")
                val keyTag = when {
                    isPk -> " PK"
                    isFk -> " FK"
                    else -> ""
                }
                val restMeta = col.metadata.filter { it != "PK" && it != "FK" }
                val metaStr = if (restMeta.isNotEmpty()) " \"${restMeta.joinToString(", ")}\"" else ""
                lines.add("        ${col.type} ${col.name}$keyTag$metaStr")
            }
            lines.add("    }")
        }

        val allRelations = tables.flatMap { it.relations }
            .distinctBy { "${it.fromTable}-${it.toTable}-${it.foreignKey}" }

        for (rel in allRelations) {
            lines.add("    ${rel.fromTable} }o--|| ${rel.toTable} : \"${rel.foreignKey}\"")
        }

        return lines.joinToString("\n")
    }

    private fun notify(project: com.intellij.openapi.project.Project, content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("SchemaToUML.Notification")
                ?.createNotification("SchemaToUML", content, type)
                ?.notify(project)
        } catch (_: Exception) {}
    }
}