package ru.sebyanin.booktranslator.core.glossary

import ru.sebyanin.booktranslator.core.log.AppLog
import ru.sebyanin.booktranslator.core.model.GlossaryEntry
import ru.sebyanin.booktranslator.core.model.GlossaryFile
import ru.sebyanin.booktranslator.core.model.KnowledgeNote
import ru.sebyanin.booktranslator.core.model.NotesFile
import ru.sebyanin.booktranslator.core.storage.ProjectStore
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Канон знаний проекта: глоссарий (термин → перевод) + «библия перевода» (заметки).
 * Канон меняет только человек; агенты (через Терминолога) лишь ПРОИЗВОДЯТ предложения.
 */
class KnowledgeService(private val store: ProjectStore) {

    data class Selected(
        val dictionary: List<GlossaryEntry>,
        val notes: List<KnowledgeNote>,
    )

    private fun now(): String = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    // ---------- чтение ----------

    fun glossary(projectSlug: String): List<GlossaryEntry> = store.loadGlossary(projectSlug).entries

    fun notes(projectSlug: String): List<KnowledgeNote> = store.loadNotes(projectSlug).notes

    fun approvedTerms(projectSlug: String): List<GlossaryEntry> =
        glossary(projectSlug).filter { it.isApproved }

    fun proposed(projectSlug: String): List<GlossaryEntry> =
        glossary(projectSlug).filter { it.status == "proposed" }

    fun proposedNotes(projectSlug: String): List<KnowledgeNote> =
        notes(projectSlug).filter { it.status == "proposed" }

    /** Мини-словарь и заметки для фрагмента: только встречающиеся сущности (брифы не пухнут). */
    fun selectForFragment(projectSlug: String, srcText: String): Selected {
        val lower = srcText.lowercase()
        val approved = approvedTerms(projectSlug)
        val dictionary = approved.filter { entry ->
            (entry.original.isNotEmpty() && lower.contains(entry.original.lowercase())) ||
                lower.contains(entry.translation.lowercase())
        }
        val entities = dictionary.map { it.original.lowercase() } +
            dictionary.map { it.translation.lowercase() }
        val approvedNotes = notes(projectSlug).filter { it.status == "approved" }
        val selected = approvedNotes.filter { note ->
            entities.any { e -> e.isNotEmpty() && (note.entity.lowercase().contains(e) || e.contains(note.entity.lowercase())) }
        }
        return Selected(dictionary, selected)
    }

    // ---------- предложения (Терминолог/Корректор → очередь → человек) ----------

    /** true — предложение новое; false — дубликат/уже решено (вариант уходит в alternatives). */
    fun proposeTerm(
        projectSlug: String,
        original: String,
        translation: String,
        context: String = "",
        category: String = "term",
        reason: String = "",
    ): Boolean {
        if (original.isBlank() || translation.isBlank()) return false
        val file = store.loadGlossary(projectSlug)
        val existing = file.entries.firstOrNull { it.original == original.trim() }
        if (existing != null) {
            val candidate = translation.trim()
            var touched = false
            var updated = existing
            // У уже ожидающего предложения другой вариант — копим как альтернативу
            if (existing.status == "proposed" && existing.translation != candidate &&
                existing.alternatives.none { it.equals(candidate, ignoreCase = true) }
            ) {
                updated = updated.copy(alternatives = updated.alternatives + candidate)
                touched = true
                AppLog.info("terminologist", "Альтернатива к предложению: ${original.trim()} → $candidate")
            }
            // Уже канонизировано иначе — предлагаемый вариант фиксируем как отклонённый contender
            if (existing.isApproved && existing.translation != candidate && existing.rejectedVariants.none { it.equals(candidate, ignoreCase = true) }) {
                updated = updated.copy(rejectedVariants = updated.rejectedVariants + candidate)
                touched = true
            }
            // Пояснение из нового маркера доливаем, если у записи его ещё нет
            if (existing.status == "proposed" && existing.reason.isBlank() && reason.isNotBlank()) {
                updated = updated.copy(reason = reason.trim(), context = existing.context.ifBlank { context })
                touched = true
            }
            if (touched) {
                save(projectSlug, file.copy(entries = file.entries.map {
                    if (it.original == original.trim()) updated else it
                }))
            }
            return false
        }
        val entry = GlossaryEntry(
            original = original.trim(),
            translation = translation.trim(),
            category = category,
            status = "proposed",
            context = context,
            reason = reason.trim(),
            proposedAt = now(),
        )
        save(projectSlug, file.copy(entries = file.entries + entry))
        AppLog.info("terminologist", "Предложение термина: ${entry.original} → ${entry.translation}")
        return true
    }

    fun proposeNote(projectSlug: String, entity: String, situation: String, text: String, source: String = ""): Boolean {
        if (entity.isBlank() || text.isBlank()) return false
        val file = store.loadNotes(projectSlug)
        val dup = file.notes.any {
            it.entity.equals(entity.trim(), ignoreCase = true) &&
                it.situation.equals(situation.trim(), ignoreCase = true) &&
                it.text.equals(text.trim(), ignoreCase = true)
        }
        if (dup) return false
        val note = KnowledgeNote(
            entity = entity.trim(),
            situation = situation.trim(),
            text = text.trim(),
            status = "proposed",
            source = source,
            proposedAt = now(),
        )
        store.saveNotes(projectSlug, file.copy(notes = file.notes + note))
        AppLog.info("terminologist", "Предложение заметки: [${note.entity}] ${note.text.take(60)}")
        return true
    }

    // ---------- решения человека ----------

    fun approveTerm(projectSlug: String, original: String, translation: String) {
        val file = store.loadGlossary(projectSlug)
        save(
            projectSlug,
            file.copy(
                entries = file.entries.map {
                    if (it.original == original) it.copy(translation = translation, status = "approved", decidedAt = now()) else it
                },
            ),
        )
    }

    fun rejectTerm(projectSlug: String, original: String) {
        val file = store.loadGlossary(projectSlug)
        save(
            projectSlug,
            file.copy(
                entries = file.entries.map {
                    if (it.original == original) {
                        it.copy(status = "rejected", rejectedVariants = (it.rejectedVariants + it.translation).distinct(), decidedAt = now())
                    } else it
                },
            ),
        )
    }

    fun updateTerm(projectSlug: String, original: String, transform: (GlossaryEntry) -> GlossaryEntry) {
        val file = store.loadGlossary(projectSlug)
        save(projectSlug, file.copy(entries = file.entries.map { if (it.original == original) transform(it) else it }))
    }

    /** Термин удалён из глоссария целиком — явное решение человека. true, если запись была. */
    fun removeTerm(projectSlug: String, original: String): Boolean {
        val file = store.loadGlossary(projectSlug)
        val next = file.copy(entries = file.entries.filterNot { it.original == original })
        if (next.entries.size == file.entries.size) return false
        save(projectSlug, next)
        AppLog.info("glossary", "Термин удалён из глоссария: $original")
        return true
    }

    /** Термин, внесённый человеком вручную, — сразу канон (approved). */
    fun addManualTerm(projectSlug: String, original: String, translation: String, category: String = "term", note: String = ""): Boolean {
        if (original.isBlank() || translation.isBlank()) return false
        val file = store.loadGlossary(projectSlug)
        if (file.entries.any { it.original == original.trim() }) return false
        save(
            projectSlug,
            file.copy(
                entries = file.entries + GlossaryEntry(
                    original = original.trim(),
                    translation = translation.trim(),
                    category = category.ifBlank { "term" },
                    status = "approved",
                    note = note.trim(),
                    proposedAt = now(),
                    decidedAt = now(),
                ),
            ),
        )
        AppLog.info("glossary", "Термин добавлен вручную: ${original.trim()} → ${translation.trim()}")
        return true
    }

    /** Заметка «библии», внесённая человеком, — сразу канон. */
    fun addManualNote(projectSlug: String, entity: String, situation: String, text: String): Boolean {
        if (entity.isBlank() || text.isBlank()) return false
        val file = store.loadNotes(projectSlug)
        val dup = file.notes.any {
            it.entity.equals(entity.trim(), ignoreCase = true) && it.situation.equals(situation.trim(), ignoreCase = true)
        }
        if (dup) return false
        store.saveNotes(
            projectSlug,
            file.copy(
                notes = file.notes + KnowledgeNote(
                    entity = entity.trim(),
                    situation = situation.trim(),
                    text = text.trim(),
                    status = "approved",
                    source = "вручную",
                    proposedAt = now(),
                    decidedAt = now(),
                ),
            ),
        )
        AppLog.info("glossary", "Заметка добавлена вручную: [${entity.trim()}] ${text.trim().take(60)}")
        return true
    }

    /** Утвердить заметку; [textOverride] позволяет утвердить свой вариант текста. */
    fun approveNote(projectSlug: String, entity: String, situation: String, textOverride: String? = null) {
        val file = store.loadNotes(projectSlug)
        store.saveNotes(
            projectSlug,
            file.copy(
                notes = file.notes.map {
                    if (it.entity == entity && it.situation == situation) {
                        it.copy(
                            status = "approved",
                            text = textOverride?.trim()?.takeIf { t -> t.isNotBlank() } ?: it.text,
                            decidedAt = now(),
                        )
                    } else it
                },
            ),
        )
    }

    fun rejectNote(projectSlug: String, entity: String, situation: String) = decideNote(projectSlug, entity, situation, "rejected")

    private fun decideNote(projectSlug: String, entity: String, situation: String, status: String) {
        val file = store.loadNotes(projectSlug)
        store.saveNotes(
            projectSlug,
            file.copy(
                notes = file.notes.map {
                    if (it.entity == entity && it.situation == situation) it.copy(status = status, decidedAt = now()) else it
                },
            ),
        )
    }

    fun updateNote(projectSlug: String, entity: String, situation: String, transform: (KnowledgeNote) -> KnowledgeNote) {
        val file = store.loadNotes(projectSlug)
        store.saveNotes(
            projectSlug,
            file.copy(
                notes = file.notes.map {
                    if (it.entity == entity && it.situation == situation) transform(it) else it
                },
            ),
        )
    }

    /** Заметка удалена из «библии» целиком — явное решение человека. true, если заметка была. */
    fun removeNote(projectSlug: String, entity: String, situation: String): Boolean {
        val file = store.loadNotes(projectSlug)
        val next = file.copy(notes = file.notes.filterNot { it.entity == entity && it.situation == situation })
        if (next.notes.size == file.notes.size) return false
        store.saveNotes(projectSlug, next)
        AppLog.info("glossary", "Заметка удалена из «библии»: [$entity]")
        return true
    }

    private fun save(projectSlug: String, file: GlossaryFile) = store.saveGlossary(projectSlug, file)
}
