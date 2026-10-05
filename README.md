# Android Mock Server

Библиотека HTTP-моков для Android/JVM: приложение описывает ответы в Kotlin DSL и передаёт движок своему OkHttp или Ktor Client. Подходит для разработки без backend, демонстрационных сценариев и тестов клиентского кода.

Моки работают внутри процесса: библиотека не открывает порт и перехватывает только явно настроенные клиенты. Несовпавший запрос завершается типизированной ошибкой без выхода в сеть. Чтобы получить HTTP `404`, задайте правило с `status(404)`.

## Содержание

- [Подключение](#подключение)
- [Быстрый старт](#быстрый-старт)
- [Правила и сопоставление запросов](#правила-и-сопоставление-запросов)
- [Ответы](#ответы)
- [Сценарии round-robin](#сценарии-round-robin)
- [Динамические ответы](#динамические-ответы)
- [Обновление правил во время работы](#обновление-правил-во-время-работы)
- [Прямое использование ядра](#прямое-использование-ядра)
- [OkHttp и Retrofit](#okhttp-и-retrofit)
- [Ktor Client](#ktor-client)
- [Лимиты и ошибки](#лимиты-и-ошибки)
- [Сборка, sample и проверки](#сборка-sample-и-проверки)

## Подключение

| Модуль | Назначение |
| --- | --- |
| `mock-core` | Снимки запросов, DSL, правила, решения и счётчики сценариев. JVM-модуль без Android API и сетевых клиентов. |
| `mock-okhttp` | Application interceptor для OkHttp; Retrofit использует тот же настроенный клиент. |
| `mock-ktor` | Ktor `MockEngine`, который становится транспортом отдельного `HttpClient`. |
| `sample` | Android composition root с debug asset и instrumentation test. |

Публикация в Maven-репозиторий в проекте не настроена. Для использования подключите исходные модули к своей Gradle-сборке. Пример структуры:

```text
workspace/
├── your-app/
└── android-mock-server/
```

Добавьте в `your-app/settings.gradle.kts` нужные проекты; существующие настройки repositories и plugins сохраните:

```kotlin
include(":mock-core", ":mock-okhttp", ":mock-ktor")
project(":mock-core").projectDir = file("../android-mock-server/mock-core")
project(":mock-okhttp").projectDir = file("../android-mock-server/mock-okhttp")
project(":mock-ktor").projectDir = file("../android-mock-server/mock-ktor")
```

Для выбранных модулей нужны `google()` и `mavenCentral()` в repositories. Модули используют JDK toolchain `17` и получают версии Kotlin plugins от корневой сборки приложения. Добавьте недостающие объявления в корневой `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
}
```

Существующие объявления Android/Kotlin plugins сохраните и согласуйте их версии: `kotlin("android")` также должен использовать `2.3.20`. Для Android-сборки с этим Kotlin проверена связка AGP `8.13.2` и Gradle `8.13`. Runtime `kotlinx.serialization 1.11.0` указан в зависимостях `mock-core`; при выборе другой версии Kotlin согласуйте compiler plugins и runtime во всех подключённых модулях.

Для Android-моков только в debug-варианте добавьте в `app/build.gradle.kts`:

```kotlin
dependencies {
    // Реальный клиент нужен и в release; если он уже подключён, сохраните свою версию.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation(project(":mock-core"))
    debugImplementation(project(":mock-okhttp"))
    // Нужно только при использовании Ktor Client:
    debugImplementation(project(":mock-ktor"))
}
```

В JVM-проекте или варианте, где моки нужны постоянно, используйте `implementation(project(...))`. Отдельного Retrofit runtime-модуля нет. Сам Retrofit и нужный converter подключает приложение.

### Выбор реального клиента и моков через source sets

Код приложения получает обычный `OkHttpClient` через свою DI-фабрику. Реализацию фабрики можно разместить отдельно в `src/debug` и `src/release`: тогда release-код не зависит от mock-модулей. Фабрика ниже дополнительно позволяет в debug выбрать реальную сеть через `useMocks = false`; этот выбор делается при создании клиента.

Если общий код находится в `src/main`, передавайте туда клиент или интерфейс своего repository. Типы `RuleEngine` и `MockInterceptor` при `debugImplementation` остаются в debug-коде.

## Быстрый старт

Законченный пример для `app/src/debug/kotlin/com/example/network/ApiClient.kt`:

```kotlin
package com.example.network

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.okhttp.MockInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request

fun createApiClient(useMocks: Boolean = true): OkHttpClient {
    val builder = OkHttpClient.Builder()
    if (useMocks) {
        val engine = RuleEngine()
        val rules = mockRules {
            rule(id = "users", path = "/v1/users") {
                match {
                    method("GET")
                    host("api.example.test")
                    query { containsValue("page", "1") }
                }
                respond {
                    header("Content-Type", "application/json; charset=utf-8")
                    bodyText("""{"users":[{"id":1,"name":"Ada"}]}""")
                }
            }
        }
        engine.replaceRules(rules)
        builder.addInterceptor(MockInterceptor(engine))
    }
    return builder.build()
}

// Синхронный execute() вызывайте на фоновом потоке приложения.
fun loadUsers(client: OkHttpClient): String {
    val request = Request.Builder()
        .url("https://api.example.test/v1/users?page=1")
        .build()
    return client.newCall(request).execute().use { response ->
        check(response.isSuccessful)
        checkNotNull(response.body).string()
    }
}
```

В `app/src/release/kotlin/com/example/network/ApiClient.kt` фабрика с тем же именем:

```kotlin
package com.example.network

import okhttp3.OkHttpClient

fun createApiClient(): OkHttpClient = OkHttpClient.Builder().build()
```

Общий код использует `createApiClient()` и сохраняет полученный клиент в своём DI scope. В debug будет выбран мок, в release — реальный транспорт. Для runtime-переключения создайте другой клиент и обновите DI binding; уже созданный клиент сохраняет свой адаптер.

`mockRules` возвращает `List<Rule>` и ничего не публикует. Публикация происходит в `engine.replaceRules(rules)`. Один engine можно передать нескольким клиентам: они будут делить таблицу и счётчики. Разные экземпляры engine независимы.

## Правила и сопоставление запросов

Каждое `rule(id, path)` задаёт непустой уникальный ID, точный encoded path и ровно один ответ: `respond`, `roundRobin` или `respondWith`. Путь обязателен, начинается с `/` и не содержит query или fragment. Без блока `match` правило подходит ко всем методам и хостам с этим путём.

Порядок выбора: больший `priority` первым, затем порядок регистрации. Приоритет по умолчанию `0`. Возвращается первое совпадение; ошибка его callback или ответа завершает запрос, поиск следующего правила не продолжается.

Фрагмент внутри `mockRules { ... }`:

```kotlin
rule("create-order", "/v1/orders") {
    priority(20)
    match {
        method("POST")
        scheme("https")
        host("api.example.test")
        port(443)
        query {
            containsValue("tag", "new")
            containsValue("preview", null)
        }
        headers { contains("Accept", "application/json") }
        bodyExactUtf8("create")
    }
    respond { status(201); bodyText("created") }
}
```

Все условия, включая путь, соединяются через **И**. Повторные вызовы `match` добавляют условия, а не заменяют их. Например, два вызова `method("GET")` и `method("POST")` внутри одного правила не образуют альтернативу.

| Операция | Смысл |
| --- | --- |
| `method(value)` | Точное совпадение метода после приведения к верхнему регистру. |
| `scheme(value)` | `http` или `https`, без учёта регистра. |
| `host(value)` | Точное совпадение хоста без учёта регистра. |
| `port(value)` | Эффективный порт `1..65535`; без явного порта URL используются `80` для HTTP и `443` для HTTPS. |
| `query { containsValue(name, value) }` | Существует хотя бы одна точно совпавшая пара. |
| `query { allValues(name, value) }` | Имя присутствует и все его значения равны заданному. |
| `query { exactList(...) }` | Совпадает весь упорядоченный список `QueryEntry`, включая повторы. |
| `headers { contains(name, value) }` | Среди повторов заголовка есть точное значение. Регистр имени не учитывается; регистр значения учитывается. |
| `bodyExactBytes(bytes)` / `bodyExactUtf8(text)` | Доступное тело точно равно байтам; текст кодируется UTF-8. |
| `bodyContainsBytes(bytes)` / `bodyContainsUtf8(text)` | Доступное тело содержит последовательность байтов. |
| `matching { request -> ... }` | Пользовательский predicate в блоке правила, добавленный через И к остальным условиям. |

Для **ИЛИ** используйте несколько правил либо predicate. Фрагмент внутри `mockRules { ... }`:

```kotlin
rule("read-users", "/v1/users") {
    matching { request -> request.method == "GET" || request.method == "HEAD" }
    respond { bodyText("users") }
}
```

### URL, query и доступность тела

Ядро принимает HTTP(S)-URL без user-info и fragment. Путь сравнивается в encoded-виде: hex-цифры percent escapes приводятся к верхнему регистру, но `/a` и `/%61` остаются разными путями. Regex и prefix/suffix path в DSL отсутствуют.

Query декодируется как UTF-8; `+` остаётся плюсом. Имена и значения чувствительны к регистру. `?flag` даёт `null`, а `?flag=` — пустую строку. Для URL `?tag=a&tag=b&flag&empty=` условие полного совпадения выглядит так; добавьте импорт `dev.androidmock.core.request.QueryEntry`:

```kotlin
query {
    exactList(
        QueryEntry("tag", "a"),
        QueryEntry("tag", "b"),
        QueryEntry("flag", null),
        QueryEntry("empty", ""),
    )
}
```

Это фрагмент блока `match`. Если порядок query не важен, задавайте отдельные `containsValue`/`allValues` вместо `exactList`.

Body predicates работают только с `RequestBodySnapshot.Buffered`. При `Absent` или `Unavailable` они возвращают `false`; пустой буфер отличается от отсутствующего тела. JSON сравнивается как байты, без нормализации пробелов и порядка полей. Политику захвата исходящего тела настраивает [адаптер](#okhttp-и-retrofit).

## Ответы

В `respond { ... }` и в каждом `roundRobin.response { ... }` доступны одинаковые операции:

| Операция | Поведение |
| --- | --- |
| `status(code)` | HTTP-статус `200..599`; по умолчанию `200`. |
| `header(name, value)` | Добавляет заголовок; повторные имена и значения сохраняются. |
| `bodyText(text)` | UTF-8 bytes. `Content-Type` задаётся вручную. |
| `bodyBytes(bytes)` | Конечный массив байтов, скопированный в ответ. |
| `bodyJson(value)` | Сериализация готовой модели в UTF-8 JSON. |
| `bodyJson<Model> { ... }` | Заполнение модели через поля JSON с проверкой сериализатором. |
| `bodyAsset(path)` | Однократное чтение тела через `BodyAssetResolver` при построении правил. |
| `delay(duration)` | Задержка доставки ответа; по умолчанию нулевая. |

Тело можно задать один раз. Без body-операции оно отсутствует. Для `204` и `304` тело запрещено даже в виде пустого `bodyBytes`; используйте только `status(...)`. `HEAD` получает пустое тело на уровне адаптера, хотя правило может содержать body.

### JSON из готовой модели и частичное заполнение

Модели приложения должны иметь сгенерированный сериализатор. Примените `kotlin("plugin.serialization")` той же версии, что Kotlin в приложении; в текущей конфигурации модулей это `2.3.20`. Проект использует совместимый runtime `kotlinx.serialization 1.11.0`.

Пример отдельного Kotlin-файла с двумя способами построения правил:

```kotlin
import dev.androidmock.core.dsl.mockRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProfileDto(val city: String, val verified: Boolean)

@Serializable
data class UserDto(
    @SerialName("user_id") val id: Int,
    val name: String = "Guest",
    val profile: ProfileDto,
    val roles: List<String>,
    val nickname: String?,
)

val jsonRules = mockRules {
    rule("user-ready", "/v1/users/1") {
        respond {
            bodyJson(
                UserDto(1, "Ada", ProfileDto("Tomsk", true), listOf("reader"), null)
            )
        }
    }
    rule("user-partial", "/v1/users/2") {
        respond {
            bodyJson<UserDto> {
                field("user_id", 2)
                objectField("profile") { field("city", "Tomsk") }
                field("nickname", null)
            }
        }
    }
}
```

Во втором правиле `name` будет `"Guest"` из Kotlin default модели, `profile.verified` — `false`, `roles` — пустым списком. Порядок заполнения: **явное поле → Kotlin default → стабильное значение по типу**. `field` принимает JSON-имя из `@SerialName`. `objectField` заполняет вложенный объект по его дескриптору; можно также передать готовую модель через `field("profile", ProfileDto(...))`.

| Пропущенное обязательное поле без Kotlin default | Значение |
| --- | --- |
| Целые числа / дробные числа | `0` / `0.0` |
| `Boolean`, `String`, `Char` | `false`, `""`, `\u0000` |
| Nullable | `null` |
| List / Map | Пустая коллекция |
| Enum | Первый объявленный элемент |
| Вложенная модель | Рекурсивно заполненные обязательные поля |

Необязательные поля оставляются декодеру модели, чтобы применился её Kotlin default. Полиморфный, контекстный, inline/custom тип или обязательный цикл, для которого значение нельзя вывести, требует явного заполнения. Неизвестные и повторные поля, неверный тип или невозможность декодировать модель дают `ConfigurationException` при построении правил.

Обе формы `bodyJson` добавляют `Content-Type: application/json; charset=utf-8`, если такого заголовка ещё нет. Явное значение сохраняется независимо от регистра имени. JSON включает Kotlin defaults и явные `null`: сериализатор настроен с `encodeDefaults = true` и `explicitNulls = true`. Для своего сериализатора доступны `bodyJson(value, serializer)`, `bodyJson(serializer) { ... }` и `field(name, value, serializer)`.

JSON строится до публикации: engine хранит готовые bytes и не сериализует модель во время запроса.

### Тела из Android assets

Правила держите, например, в `app/src/debug/kotlin`, а файлы ответов — в `app/src/debug/assets/mock/bodies`. Файл `receipt.json`:

```json
{"orderId":42}
```

Resolver связывает относительный путь DSL с доверенным префиксом assets и ограничивает чтение. Пример функции для debug source set:

```kotlin
import android.content.Context
import dev.androidmock.core.dsl.BodyAssetResolver
import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.EngineLimits
import dev.androidmock.core.rule.Rule
import java.io.ByteArrayOutputStream

fun loadAssetRules(context: Context, limits: EngineLimits = EngineLimits()): List<Rule> {
    val resolver = BodyAssetResolver { path ->
        context.assets.open("mock/bodies/$path").use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(count <= limits.maxResponseBytes - output.size()) {
                    "Mock asset exceeds response limit"
                }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
    }
    return mockRules(bodyAssets = resolver) {
        rule("receipt", "/v1/receipt") {
            match { method("GET") }
            respond {
                header("Content-Type", "application/json")
                bodyAsset("receipt.json")
            }
        }
    }
}
```

Вызов `loadAssetRules` выполняйте вне главного потока, затем публикуйте результат через `engine.replaceRules(...)`. Чтение синхронное и происходит при построении правил. `decide` после публикации не открывает assets; поток уже закрыт, байты принадлежат ответу.

DSL отклоняет абсолютные пути, `.`/`..`, обратную косую черту и пустые сегменты. Отсутствующий resolver или ошибка чтения дают `ConfigurationException`. В JVM-тесте тот же интерфейс может возвращать bytes из памяти или test resources. Не используйте `InputStream.available()` как размер файла.

### Задержка

```kotlin
import dev.androidmock.core.dsl.mockRules
import kotlin.time.Duration.Companion.milliseconds

val delayedRules = mockRules {
    rule("slow-users", "/v1/users") {
        respond {
            delay(250.milliseconds)
            bodyText("users")
        }
    }
}
```

`delay` должна быть конечной и неотрицательной. Ядро выбирает ответ сразу; ожидание реализуют адаптеры перед доставкой. Прямой вызов `engine.decide` не ждёт. Для OkHttp ожидание блокирует поток вызова, поэтому синхронные запросы выполняйте вне Android main thread. Ktor ожидает через отменяемый coroutine `delay`.

## Сценарии round-robin

Последовательность циклическая: после последнего ответа выбирается первый. Непустой список ответов обязателен. Пример отдельного набора правил:

```kotlin
import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.rule.ScenarioKey

val checkoutRules = mockRules {
    rule("checkout-read", "/v1/checkout") {
        match { method("GET") }
        roundRobin {
            scenarioKey(ScenarioKey.Shared("checkout"))
            response { bodyText("read-first") }
            response { bodyText("read-second") }
        }
    }
    rule("checkout-write", "/v1/checkout") {
        match { method("POST") }
        roundRobin {
            scenarioKey(ScenarioKey.Shared("checkout"))
            response { status(503); bodyText("write-first") }
            response { status(201); bodyText("write-second") }
        }
    }
}
```

Без `scenarioKey` используется собственный `ScenarioKey.PerRule(RuleId(...))`. В примере оба правила делят один курсор `Shared("checkout")`: запросы `GET → POST → GET` получают слоты `0 → 1 → 2` и ответы `read-first → write-second → read-first`. Каждый responder выбирает `slot % responses.size` из своего списка.

`Shared("checkout")` и `PerRule(RuleId("checkout"))` различаются. Ключ статичен для правила: method, host, query и callback не выбирают его на каждом запросе. Разные engine не разделяют курсоры даже при одинаковом имени ключа.

Выбор слота атомарен. Для параллельных запросов порядок доставки ответов не обещан. Слот расходуется в момент выбора ответа и не возвращается при отмене клиента. `engine.resetScenarios()` начинает все сценарии заново; точечного `resetScenarios(key)` в API нет. Сброс затронутых ключей при `upsertRule`/`removeRule` описан [ниже](#обновление-правил-во-время-работы).

## Динамические ответы

`respondWith` получает неизменяемый снимок запроса и `ResponderContext`, у которого публично доступен `ruleId`. Он возвращает обычный `ResponseSpec`:

```kotlin
import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.response.ResponseBody
import dev.androidmock.core.response.ResponseSpec

val dynamicRules = mockRules {
    rule("price", "/v1/price") {
        match { method("GET") }
        respondWith { request, context ->
            val currency = request.url.query
                .firstOrNull { it.name == "currency" }?.value ?: "USD"
            ResponseSpec(
                headers = listOf(HeaderEntry("Content-Type", "text/plain; charset=utf-8")),
                body = ResponseBody.Bytes(
                    "rule=${context.ruleId.value}; currency=$currency".toByteArray(Charsets.UTF_8)
                ),
            )
        }
    }
}
```

И `matching`, и `respondWith` выполняются синхронно внутри `decide`, могут вызываться конкурентно и должны быть быстрыми и потокобезопасными. Не выполняйте в них I/O, блокирующее ожидание или изменение таблицы правил. Контекст не предоставляет доступ к произвольному сценарию, клиенту или сети. Пользовательский код вызывается после проверки пути и вне блокировки публикации.

Исключение callback даёт `MockFailure.CallbackFailure`; `CancellationException` пробрасывается без преобразования. Каждый вычисленный `ResponseSpec` проверяется перед возвратом. Невалидный результат даёт `MockFailure.InvalidResponse` и не приводит к выбору другого правила.

## Обновление правил во время работы

Engine сохраняйте в DI scope приложения или теста и обновляйте тот же экземпляр, который передан клиентам. Пример самостоятельного Kotlin-кода:

```kotlin
import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.rule.RuleId

fun demonstrateUpdates(): Boolean {
    val engine = RuleEngine()
    engine.replaceRules(mockRules {
        rule("users", "/v1/users") { respond { bodyText("first version") } }
    })
    val updatedRule = mockRules {
        rule("users", "/v1/users") { respond { bodyText("second version") } }
    }.single()
    engine.upsertRule(updatedRule)
    engine.resetScenarios()
    return engine.removeRule(RuleId("users"))
}
```

| Операция | Таблица и счётчики |
| --- | --- |
| `replaceRules(rules)` | Полностью заменяет набор. Все сценарии начинаются с нуля, даже для тех же ID и тех же правил. Пустой список отключает все совпадения. |
| `upsertRule(rule)` | Заменяет правило с тем же ID, сохраняя позицию регистрации; новый ID добавляет в конец. Сбрасывает старый и новый сценарные ключи затронутого правила. Остальные продолжаются. |
| `removeRule(id)` | Возвращает `true` при удалении и сбрасывает его ключ у оставшихся правил. Если ID отсутствует, возвращает `false` и не меняет состояние. |
| `resetScenarios()` | Сохраняет таблицу и начинает все её сценарии с нуля. |

Если затронутый ключ — `Shared`, его последовательность начинается заново для **всех** правил с этим ключом. Повторный `upsertRule` того же объекта также сбрасывает затронутые ключи.

Проверка набора происходит до атомарной публикации. Ошибка построения DSL или отклонённое обновление сохраняют прежние правила и курсоры. Конкурентный запрос захватывает одну версию таблицы вместе с её счётчиками: начатый до обновления может завершиться по старой версии, новый после публикации видит новую. Callback не удерживает блокировку изменения правил.

## Прямое использование ядра

Для unit-теста или своего адаптера достаточно `mock-core`. Kotlin DSL создаёт обычные `Rule`, но их можно построить непосредственно:

```kotlin
import dev.androidmock.core.engine.Decision
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.request.HeaderEntry
import dev.androidmock.core.request.RequestBodySnapshot
import dev.androidmock.core.request.RequestSnapshots
import dev.androidmock.core.response.ResponseBody
import dev.androidmock.core.response.ResponseSpec
import dev.androidmock.core.rule.ExactPath
import dev.androidmock.core.rule.RequestMatcher
import dev.androidmock.core.rule.Rule
import dev.androidmock.core.rule.RuleId
import dev.androidmock.core.rule.StaticResponder

fun checkPing(): String {
    val engine = RuleEngine()
    engine.replaceRules(listOf(
        Rule(
            id = RuleId("ping"),
            path = ExactPath("/ping"),
            matcher = RequestMatcher { it.method == "POST" },
            responder = StaticResponder(
                ResponseSpec(body = ResponseBody.Bytes("pong".toByteArray(Charsets.UTF_8)))
            ),
        )
    ))
    val request = RequestSnapshots.fromUrl(
        method = "POST",
        originalUrl = "https://api.example.test/ping",
        headers = listOf(HeaderEntry("Accept", "text/plain")),
        body = RequestBodySnapshot.Buffered("ping".toByteArray(Charsets.UTF_8)),
    )
    return when (val decision = engine.decide(request)) {
        is Decision.Mock -> when (val body = decision.response.body) {
            ResponseBody.Empty -> ""
            is ResponseBody.Bytes -> body.bytes.toString(Charsets.UTF_8)
        }
        is Decision.Fail -> error("Mock failed: ${decision.error.javaClass.simpleName}")
    }
}
```

`RequestSnapshots.fromUrl` проверяет метод, URL и заголовки; при неверном входе бросает `InvalidRequestException`. Входные byte arrays и коллекции копируются на границах. `Decision.Mock` содержит `response` и `ruleId`, `Decision.Fail` — `error`. Engine сам не открывает сеть, не ждёт `response.delay` и не создаёт потоков или coroutine scope.

| Состояние тела запроса | Смысл |
| --- | --- |
| `RequestBodySnapshot.Absent` | Тело не было задано. |
| `RequestBodySnapshot.Buffered(bytes)` | Байты доступны для body predicates; пустой массив допустим. |
| `RequestBodySnapshot.Unavailable(reason)` | Тело существует, но не захвачено. Причины: `ONE_SHOT`, `STREAMING`, `TOO_LARGE`, `UNSUPPORTED`. |

Публичные типы распределены по пакетам `dev.androidmock.core.request`, `.response`, `.rule`, `.engine` и `.dsl`.

## OkHttp и Retrofit

Подключайте `MockInterceptor` через `addInterceptor` в тот `OkHttpClient`, который передаётся приложению. Это application interceptor: сетевой transport не вызывается. Interceptors, добавленные раньше него, могут подготовить запрос; mock-адаптер завершает цепочку, поэтому расположенные после него не вызываются.

Пример фабрики с захватом небольшого повторяемого тела:

```kotlin
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.okhttp.MockInterceptor
import dev.androidmock.okhttp.RequestBodyCapture
import okhttp3.OkHttpClient

fun createMockOkHttp(engine: RuleEngine): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(MockInterceptor(
        engine = engine,
        bodyCapture = RequestBodyCapture.REPEATABLE,
        maxRequestBytes = 1024 * 1024L,
    ))
    .build()
```

| Исходящее тело | Снимок |
| --- | --- |
| Тела нет | `Absent` |
| One-shot | `Unavailable(ONE_SHOT)`; не читается |
| Duplex | `Unavailable(STREAMING)`; не читается |
| `RequestBodyCapture.NONE` — по умолчанию | `Unavailable(UNSUPPORTED)` для прочих тел; не читается |
| `REPEATABLE`, но длина неизвестна | `Unavailable(STREAMING)`; не читается |
| `REPEATABLE`, длина или фактически записанные bytes больше лимита | `Unavailable(TOO_LARGE)` |
| `REPEATABLE`, известная длина в пределах лимита | `Buffered` после ограниченного захвата |

`REPEATABLE` вызывает `RequestBody.writeTo` для захвата, поэтому используйте его только для тел, чьё повторное создание безопасно. Превышенный входной лимит не означает автоматический отказ запроса: правила без условий тела всё ещё могут совпасть. Ошибки чтения самого пользовательского `RequestBody` могут пробрасываться как обычные исключения, без `MockFailure`.

Промах, ошибка callback или невалидный ответ дают `MockIOException`, его поле `failure` содержит `MockFailure`. Отмена вызова и прерывание ожидания дают `InterruptedIOException`. Задержка округляется вверх до миллисекунд; проверка отмены выполняется с интервалом до 20 мс во время ожидания.

Для Retrofit достаточно этого клиента. Пример сервиса с сырым телом, которому не нужен JSON converter:

```kotlin
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET

interface UsersApi {
    @GET("v1/users?page=1")
    suspend fun users(): Response<ResponseBody>
}

fun createUsersApi(client: OkHttpClient): UsersApi = Retrofit.Builder()
    .baseUrl("https://api.example.test/")
    .client(client)
    .build()
    .create(UsersApi::class.java)
```

Для возврата своего DTO добавьте converter приложения. `bodyJson` отвечает за формирование mock bytes и не устанавливает Retrofit converter. Ответы и их тела закрывайте обычным способом; пример с `response.use` находится в [быстром старте](#быстрый-старт). HTTP-статус ошибки из правила, например `503`, остаётся обычным HTTP-ответом, а `NoMatchingRule` — транспортной ошибкой.

## Ktor Client

`mockKtorEngine` устанавливается при создании отдельного `HttpClient` и становится его единственным транспортом. Пример фабрики и suspend-запроса:

```kotlin
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.ktor.mockKtorEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

fun createMockKtor(engine: RuleEngine): HttpClient = HttpClient(
    mockKtorEngine(engine, maxRequestBytes = 1024 * 1024L)
) {
    expectSuccess = false
    // Здесь устанавливаются client plugins приложения.
}

suspend fun loadKtorUsers(client: HttpClient): String =
    client.get("https://api.example.test/v1/users?page=1").bodyAsText()
```

Владелец клиента вызывает `client.close()` по завершении своего scope. Обычные client plugins проходят путь подготовки запроса и обработки ответа; для DTO нужны стандартные настройки клиента. При `expectSuccess = true` Ktor может дополнительно превратить HTTP-статусы ошибок в собственные исключения.

| Тело после преобразования Ktor plugins | Снимок |
| --- | --- |
| `OutgoingContent.NoContent` | `Absent` |
| `OutgoingContent.ByteArrayContent` в пределах лимита | `Buffered` |
| ByteArrayContent с известной длиной или bytes больше лимита | `Unavailable(TOO_LARGE)` |
| `ReadChannelContent` / `WriteChannelContent` | `Unavailable(STREAMING)`; каналы не читаются |
| Другие формы | `Unavailable(UNSUPPORTED)` |

Промах и ошибки engine дают `KtorMockException` с полем `failure`. Coroutine cancellation сохраняется, задержка реализована через `delay`. К уже созданному клиенту с реальным engine этот адаптер не добавляется: смена транспорта требует другого `HttpClient`.

## Лимиты и ошибки

| Настройка | По умолчанию | Где задаётся |
| --- | --- | --- |
| `maxResponseBytes` | 1 MiB (`1_048_576`) | `EngineLimits`, применяется к каждому ответу |
| `maxTableBytes` | 16 MiB (`16_777_216`) | `EngineLimits`, сумма тел static и round-robin ответов в таблице |
| `maxRequestBytes` | 1 MiB | Отдельно в `MockInterceptor` и `mockKtorEngine` |

Лимиты положительные. Пример настройки engine:

```kotlin
import dev.androidmock.core.engine.EngineLimits
import dev.androidmock.core.engine.RuleEngine

val boundedEngine = RuleEngine(EngineLimits(
    maxResponseBytes = 512 * 1024,
    maxTableBytes = 8L * 1024 * 1024,
))
```

`mockRules` предварительно валидирует результат с **обычными** `EngineLimits()` во временном engine. Поэтому набор DSL сначала ограничен 1 MiB на ответ и 16 MiB на таблицу; более строгий целевой engine проверяет его снова при публикации. Если нужны большие static-ответы, создайте `Rule` напрямую и публикуйте в engine с соответствующими лимитами. Resolver assets отдельно ограничивает чтение ещё до создания `ResponseSpec`.

В сумму таблицы входят все заранее заданные ответы, включая каждый элемент round-robin. Динамическое тело не известно при публикации и не входит в эту сумму; каждый вычисленный ответ проверяется по `maxResponseBytes` при `decide`.

При построении/публикации проверяются ID, дубли правил, path, параметры DSL, заголовки, ровно один responder, непустой round-robin, статус `200..599`, конечная неотрицательная задержка, отсутствие тела для `204`/`304` и размеры. Прямые конструкторы отдельных типов также могут бросать `IllegalArgumentException` до публикации.

| Ошибка | Где и как проявляется |
| --- | --- |
| `ConfigurationException` | Построение DSL или отклонённый `replaceRules`/`upsertRule`; прежний набор остаётся активным. |
| `InvalidRequestException` | Неверный вход `RequestSnapshots.fromUrl`; адаптер переводит его в `MockFailure.InvalidRequest`. |
| `MockFailure.NoMatchingRule` | Ни одно правило не совпало. Это ошибка вызова, а не автоматический HTTP `404`. |
| `MockFailure.CallbackFailure` | Исключение matcher/responder; `cause` доступна для диагностики. |
| `MockFailure.InvalidResponse` | Вычисленный ответ нарушает контракт или лимит. |
| `MockIOException` / `KtorMockException` | Представление `MockFailure` в соответствующем клиенте. |

При повторной загрузке правил перехватывайте `ConfigurationException` у владельца engine и сохраняйте прежний клиент. При первом запуске ошибка построения должна помешать подключению неполного mock-набора.

Для разбора ошибки OkHttp пример принимает уже созданные клиент и запрос:

```kotlin
import dev.androidmock.core.engine.MockFailure
import dev.androidmock.okhttp.MockIOException
import okhttp3.OkHttpClient
import okhttp3.Request

fun executeMock(client: OkHttpClient, request: Request): String {
    try {
        return client.newCall(request).execute().use { response ->
            response.body?.string().orEmpty()
        }
    } catch (e: MockIOException) {
        if (e.failure == MockFailure.NoMatchingRule) {
            error("Add a mock rule for this request")
        }
        throw e
    }
}
```

Для Ktor аналогично проверяйте `KtorMockException.failure`. Не поглощайте отмену клиента. Диагностируйте по ID/типу ошибки; полные URL с query, токены, тела и cause могут содержать чувствительные данные.

## Сборка, sample и проверки

| Компонент | Конфигурация в репозитории |
| --- | --- |
| JDK / Gradle Wrapper | Toolchain 17 / 8.13 |
| Kotlin / serialization plugin | 2.3.20 / 2.3.20 |
| Serialization runtime | 1.11.0 |
| Android Gradle Plugin | 8.13.2 |
| `sample` | `minSdk = 23`, `compileSdk = 36`, `targetSdk = 36` |
| OkHttp / Ktor Client | 4.12.0 / 3.0.3 |
| Retrofit в integration test | 3.0.0 |

Из корня репозитория запускайте:

```shell
./gradlew :mock-core:test :mock-okhttp:test :mock-ktor:test
./gradlew :sample:assembleDebug :sample:lintDebug
./gradlew :sample:assembleDebugAndroidTest
```

Для полного штатного Gradle build также доступен `./gradlew build`. JVM tests проверяют ядро, DSL, публикацию и сценарии, OkHttp/Retrofit и Ktor. Android SDK нужен для `sample`; устройство требуется только для instrumentation:

```shell
./gradlew :sample:connectedDebugAndroidTest
```

`sample` содержит [DebugMocks.kt](sample/src/debug/kotlin/dev/androidmock/sample/DebugMocks.kt): composition root строит правила из debug asset и подключает один engine к обоим клиентам. Это пример интеграции без пользовательского экрана выбора сценариев. [PackagedAssetTest.kt](sample/src/androidTest/kotlin/dev/androidmock/sample/PackagedAssetTest.kt) проверяет чтение упакованного файла через `targetContext.assets` и отсутствие повторного чтения при `decide`.

В указанной конфигурации прошла команда `./gradlew build :sample:assembleDebugAndroidTest`: 46 JVM-тестов без ошибок, debug/release APK и debug test APK собраны, `lintDebug` завершился без ошибок. Instrumentation ранее прошёл на Android 36 AVD (`Medium_Phone`, 1 тест); при обновлении toolchain не перезапускался. Другие Android API levels и вариант реального приложения-потребителя ещё не проверены.
