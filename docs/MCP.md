# Подключение ZCode к BookTranslator через MCP

## Включение сервера

1. В приложении: **Настройки → MCP → сервер «включён»**, при желании задайте порт
   (по умолчанию 8420) и токен.
2. Адрес: `http://127.0.0.1:8420/mcp` (Streamable HTTP, только localhost).

## Конфигурация в ZCode

В настройках MCP ZCode (пользовательский или workspace-уровень) добавьте сервер:

```json
{
  "mcpServers": {
    "book-translator": {
      "type": "http",
      "url": "http://127.0.0.1:8420/mcp",
      "headers": {
        "Authorization": "Bearer <ваш-токен>"
      }
    }
  }
}
```

Если токен не задан, заголовок не нужен.

## Инструменты

| Инструмент | Назначение |
|---|---|
| `list_projects` | Проекты (slug, имя, языки) |
| `list_books` | Книги проекта |
| `get_book_status` | Фрагменты, готовность черновика/редактуры, ошибки |
| `run_stage` | Запустить `split / draft / edit / critic / revisor / revisor_canon / qa / build_epub / build_fb2` (опц. `chapters`) |
| `get_glossary` | Глоссарий (`filter: approved / proposed / all`) |
| `get_notes` | «Библия перевода» (голоса, манеры) |
| `resolve_proposals` | Утвердить/отклонить предложение Терминолога |
| `run_glossary_revisor` | Прогнать LLM-ревизора (`scope: queue` — очередь, `canon` — канон; тратит токены) |
| `get_glossary_revision` | Решения ревизора (keep/drop/fix, статусы pending/applied/dismissed) |
| `search_alignment` | Как фраза уже переводилась (`scope: translation / source`) |
| `read_chapter` | Текст главы (`which: source / draft / edited`) |
| `get_critic_suggestions` | Предложения корректора по главе (+ СОМНЕНИЯ со статусами, + пометки переводчика [[??: …]]) |
| `resolve_suggestion` | Применить/отклонить предложение (`apply` проверяет цитату count==1) |
| `resolve_doubt` | Сомнение корректора dN-M — отметить разобранным; пометка переводчика tN-… — убрать [[??: …]] из текста |

## Пример сеанса

```text
Пользователь: переведи третью главу,Terminator-проект, книга vol1

ZCode: → list_projects, list_books, get_book_status
       → run_stage(stage="draft", chapters=[3])
       → get_book_status  (дождаться done)
       → run_stage(stage="edit", chapters=[3])
       → get_critic_suggestions(chapter=3)
       ← показывает человеку список «было → стало → почему»
       → resolve_suggestion(action="apply", id="c3-1") — по решению человека
```

## Принципы

- Правки корректора и изменения глоссария принимает только человек
  (через UI или подтверждённые вызовы `resolve_*`).
- Параллельно работает только один этап; `run_stage` честно ответит «занято».
- Файлы проекта человекочитаемы — можно работать и напрямую
  (`<Documents>/BookTranslator/projects/…`), конвейер продолжит с любого места.
