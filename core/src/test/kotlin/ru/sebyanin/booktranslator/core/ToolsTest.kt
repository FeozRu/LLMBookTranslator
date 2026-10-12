package ru.sebyanin.booktranslator.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sebyanin.booktranslator.core.glossary.KnowledgeService
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import ru.sebyanin.booktranslator.core.tools.ToolFacade
import java.nio.file.Files

/** Схемы инструментов должны проходить строгую валидацию провайдеров (урок DeepSeek 400). */
class ToolsTest {

    @Test
    fun `schema properties are objects, not bare primitives`() {
        val dir = Files.createTempDirectory("tools-test")
        val store = ProjectStore { dir }
        val knowledge = KnowledgeService(store)
        val registry = ToolFacade.defaultRegistry(store, knowledge)

        val specs = registry.specs()
        assertTrue(specs.size >= 5, "инструментов: ${specs.size}")

        specs.forEach { spec ->
            val schema = spec.parametersSchema.jsonObject
            val props = schema["properties"]?.jsonObject ?: return@forEach
            props.forEach { (name, value) ->
                assertTrue(
                    value is JsonObject,
                    "свойство «$name» инструмента ${spec.name} должно быть объектом {\"type\": …}, получено: $value",
                )
            }
        }
    }
}
