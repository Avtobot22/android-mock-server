# Android Mock Server

Библиотека HTTP-моков для Android и JVM. В Kotlin-коде вы описываете, какой ответ должен получить запрос, и подключаете правила к OkHttp или Ktor Client. Так можно разрабатывать приложение без готового бэкенда, показывать демо и проверять клиентский код.

Библиотека работает внутри приложения и не открывает сетевой порт. Она обрабатывает запросы только тех клиентов, к которым подключена. Если подходящего правила нет, запрос завершается ошибкой `NoMatchingRule` без обращения в сеть. HTTP `404` или `503` нужно задать отдельным ответом в правиле.

## Содержание

- [Как обрабатывается запрос](#как-обрабатывается-запрос)
- [Подключение](#подключение)
- [Быстрый старт с OkHttp и Retrofit](#быстрый-старт-с-okhttp)
- [Ktor Client](#подключение-ktor-client)
- [Правила и ответы](#правила-и-ответы)
- [Тела из Android assets](#тела-ответов-из-android-assets)
- [Обновление правил](#обновление-правил)
- [Ограничения и ошибки](#ограничения-и-ошибки)
- [Сборка и проверки](#сборка-и-проверки)

## Что есть в проекте

| Модуль | Что делает |
| --- | --- |
| `mock-core` | Хранит правила, сопоставляет запросы, выбирает ответы и ведёт счётчики сценариев. Содержит Kotlin DSL; не зависит от Android, OkHttp и Ktor. |
| `mock-okhttp` | Подключает правила через interceptor OkHttp. Работает и с Retrofit, если передать ему этот клиент. |
| `mock-ktor` | Создаёт Ktor `MockEngine` для отдельного `HttpClient`. |
| `sample` | Показывает подключение обоих клиентов и загрузку тела ответа из Android assets. Содержит тест упакованного файла. |

## Как обрабатывается запрос

1. Приложение создаёт `RuleEngine`, описывает правила через `mockRules` и публикует их через `replaceRules`.
2. Адаптер OkHttp или Ktor переводит запрос в снимок: метод, URL, заголовки и доступные байты тела.
3. Движок ищет первое подходящее правило и выбирает ответ. Для последовательности ответов он также увеличивает счётчик сценария.
4. Адаптер выдерживает заданную задержку и возвращает ответ в привычном формате клиента. Если правила нет, возвращает ошибку вызова.

Приложение продолжает обращаться к тому же API своего сетевого клиента. Мок определяется правилами, а не отдельной функцией загрузки данных. Движок и клиенты сохраняйте на время нужного сценария: создание нового движка начинает его счётчики с нуля.

## Подключение

Публикация в Maven-репозиторий пока не настроена. Подключите нужные модули из исходников. Для примера ниже репозитории лежат рядом:

```text
workspace/
├── your-app/
└── android-mock-server/
```

В `your-app/settings.gradle.kts` добавьте проекты:

```kotlin
include(":mock-core", ":mock-okhttp", ":mock-ktor")
project(":mock-core").projectDir = file("../android-mock-server/mock-core")
project(":mock-okhttp").projectDir = file("../android-mock-server/mock-okhttp")
project(":mock-ktor").projectDir = file("../android-mock-server/mock-ktor")
```

Если используете только OkHttp, `mock-ktor` можно не подключать, и наоборот. В настройках репозиториев нужны `google()` и `mavenCentral()`. Модули используют JDK toolchain 17; версии Kotlin-плагинов задаёт корневая сборка. В корневом `build.gradle.kts` добавьте недостающие объявления:

```kotlin
plugins {
    kotlin("jvm") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
}
```

Сохраните существующие Android-плагины и согласуйте версии Kotlin: `kotlin("android")` в этой конфигурации тоже использует `2.3.20`. Версии всех компонентов репозитория приведены [ниже](#сборка-и-проверки). Подключение к реальному приложению-потребителю ещё не проверено.

В Android-приложении, где моки нужны только в debug-сборке, добавьте в `app/build.gradle.kts`:

```kotlin
dependencies {
    // Клиент нужен и в release. Если он уже подключён, сохраните свою зависимость.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation(project(":mock-core"))
    debugImplementation(project(":mock-okhttp"))
    // Для Ktor вместо mock-okhttp:
    // debugImplementation(project(":mock-ktor"))
}
```

В JVM-проекте используйте `implementation(project(...))`. Retrofit и его converter подключает приложение; отдельного модуля для Retrofit нет.

## Быстрый старт с OkHttp

Создайте `app/src/debug/kotlin/com/example/network/ApiClient.kt`:

```kotlin
package com.example.network

import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.okhttp.MockInterceptor
import okhttp3.OkHttpClient

fun createApiClient(): OkHttpClient {
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
    return OkHttpClient.Builder()
        .addInterceptor(MockInterceptor(engine))
        .build()
}
```

Обычный запрос к этому клиенту получит заданный JSON. Синхронный `execute()` на Android вызывайте вне главного потока:

```kotlin
import okhttp3.OkHttpClient
import okhttp3.Request

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

Для release создайте `app/src/release/kotlin/com/example/network/ApiClient.kt` с той же фабрикой:

```kotlin
package com.example.network

import okhttp3.OkHttpClient

fun createApiClient(): OkHttpClient = OkHttpClient.Builder().build()
```

Общий код в `src/main` вызывает `createApiClient()` и сохраняет клиент для дальнейших запросов. Типы библиотеки моков остаются в `src/debug`. Если нужно переключать моки и реальную сеть во время работы, создавайте другой клиент и передавайте его вызывающему коду.

`MockInterceptor` подключается через `addInterceptor`. Interceptors перед ним могут изменить запрос; расположенные после него не вызываются. Для Retrofit передайте этот `OkHttpClient` в `.client(client)` при создании `Retrofit.Builder`. Ответ из `bodyJson` не заменяет converter, который Retrofit использует для чтения DTO.

Например, сервис Retrofit может получать тот же ответ `/v1/users?page=1`:

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

Здесь используется сырое `ResponseBody`, поэтому JSON-converter не нужен. Для метода, возвращающего DTO, настройте converter приложения. Прочитанное тело ответа закрывайте обычным способом.

## Подключение Ktor Client

Передайте тот же `RuleEngine` в `mockKtorEngine` при создании клиента:

```kotlin
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.ktor.mockKtorEngine
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

fun createMockKtor(engine: RuleEngine): HttpClient = HttpClient(
    mockKtorEngine(engine)
) {
    expectSuccess = false
    // Здесь можно устанавливать плагины клиента приложения.
}

suspend fun loadKtorUsers(client: HttpClient): String =
    client.get("https://api.example.test/v1/users?page=1").bodyAsText()
```

Сначала опубликуйте правила через `engine.replaceRules(...)`. Ktor-клиент получает их как свой единственный транспорт; к уже созданному клиенту с реальным engine этот адаптер не добавляется. При завершении работы вызовите `client.close()`.

Один `RuleEngine` можно передать нескольким клиентам: они будут использовать общие правила и счётчики. Разные экземпляры независимы.

## Правила и ответы

`mockRules { ... }` строит и проверяет `List<Rule>`. Он не меняет работающий движок: готовый список нужно передать в `engine.replaceRules(rules)`.

У каждого правила есть уникальный непустой ID и точный путь, например `/v1/users`. Путь начинается с `/` и не содержит query или fragment. В `match` доступны условия для метода, схемы, хоста, порта, query, заголовков и байтов тела. Все условия должны выполняться одновременно; без `match` правило подходит к любому запросу с указанным путём.

Правила проверяются по убыванию `priority`, затем в порядке добавления. По умолчанию приоритет равен `0`. Выбирается первое совпавшее правило. Ошибка его обработчика завершает запрос; поиск другого ответа не продолжается.

### Условия запроса и приоритет

Условия помогают различать запросы к одному пути: например, создание заказа с нужным заголовком и телом. Следующий фрагмент размещается внутри `mockRules { ... }`:

```kotlin
rule("create-preview-order", "/v1/orders") {
    priority(20)
    match {
        method("POST")
        scheme("https")
        host("api.example.test")
        port(443)
        query { containsValue("preview", "true") }
        headers { contains("Accept", "application/json") }
        bodyExactUtf8("create")
    }
    respond {
        status(201)
        header("Content-Type", "application/json")
        bodyText("""{"orderId":42}""")
    }
}
rule("other-orders", "/v1/orders") {
    respond { status(400) }
}
```

Первое правило получит запрос `POST https://api.example.test/v1/orders?preview=true` с указанными заголовком и телом. Другие запросы к `/v1/orders` получат `400` от второго правила. Больший приоритет позволяет поставить частный случай перед общим. Для проверки тела в OkHttp нужно включить его захват, как показано [ниже](#ограничения-и-ошибки).

| Условие | Что сравнивается |
| --- | --- |
| `method`, `scheme`, `host`, `port` | Метод, схема HTTP(S), хост и эффективный порт URL. Метод нормализуется в верхний регистр, схема и хост сравниваются без учёта регистра. |
| `query { containsValue(name, value) }` | Есть хотя бы одна указанная пара. |
| `query { allValues(name, value) }` | Параметр есть, и все его значения равны указанному. |
| `query { exactList(...) }` | Совпадает весь список параметров, включая порядок и повторы. |
| `headers { contains(name, value) }` | Есть заголовок с таким значением. Регистр имени не учитывается, значения сравниваются точно. |
| `bodyExactUtf8(text)` / `bodyExactBytes(bytes)` | Всё доступное тело равно указанным байтам. |
| `bodyContainsUtf8(text)` / `bodyContainsBytes(bytes)` | Тело содержит указанную последовательность байтов. |
| `matching { request -> ... }` | Выполняется дополнительная проверка приложения. |

Например, для точного query `?tag=a&tag=b&flag&empty=` добавьте импорт `dev.androidmock.core.request.QueryEntry` и внутри `match` задайте:

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

`null` означает параметр без `=`, пустая строка — пустое значение после `=`. Имена и значения query чувствительны к регистру; `+` остаётся плюсом. Путь сравнивается в закодированном виде: `/a` и `/%61` различаются.

Повторные блоки `match` добавляют условия. Два вызова `method("GET")` и `method("POST")` не задают альтернативу: используйте отдельные правила или `matching { it.method == "GET" || it.method == "POST" }`.

### Статус, заголовки и тело ответа

Через `respond` удобно задать постоянный ответ: успешную загрузку, пустой результат или HTTP-ошибку. В каждом правиле нужен ровно один вариант: `respond`, `roundRobin` или `respondWith`.

В блоке `respond` доступны:

| Операция | Результат |
| --- | --- |
| `status(code)` | Статус `200..599`, по умолчанию `200`. |
| `header(name, value)` | Добавление заголовка. Повторы сохраняются. |
| `bodyText(text)` | Текст в UTF-8; `Content-Type` задаётся отдельно. |
| `bodyBytes(bytes)` | Бинарное тело, например изображение или файл. |
| `bodyJson(...)` | JSON из сериализуемой модели. |
| `bodyAsset(path)` | Тело из файла через resolver приложения. |
| `delay(duration)` | Задержка перед доставкой ответа. |

Например, пустой ответ задаётся так, внутри `mockRules`:

```kotlin
rule("delete-order", "/v1/orders/42") {
    match { method("DELETE") }
    respond { status(204) }
}
```

Для `204` и `304` тело запрещено, включая пустой `bodyBytes`. Если тело не задано, оно отсутствует. Для `HEAD` адаптер возвращает статус и заголовки без байтов тела.

### JSON из модели

`bodyJson` сериализует готовую модель либо позволяет заполнить её поля в DSL. Подключите к модулю с моделями плагин `kotlin("plugin.serialization")` той же версии, что Kotlin.

```kotlin
import dev.androidmock.core.dsl.mockRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    @SerialName("user_id") val id: Int,
    val name: String = "Guest",
    val roles: List<String>,
)

val jsonRules = mockRules {
    rule("ready-user", "/v1/users/1") {
        respond {
            bodyJson(UserDto(1, "Ada", listOf("reader")))
        }
    }
    rule("partial-user", "/v1/users/2") {
        respond {
            bodyJson<UserDto> {
                field("user_id", 2)
            }
        }
    }
}
```

Первое правило использует готовую модель. Во втором можно задать только поля, нужные для сценария: `name` будет `"Guest"`, а `roles` — пустым списком. Сначала применяется явно заданное поле, затем значение по умолчанию из модели, затем стабильное значение по типу. `field` принимает JSON-имя из `@SerialName`; для вложенных объектов есть `objectField`. Неверные типы, неизвестные и повторные поля отклоняются при построении правил. Подробности заполнения и его ограничения описаны в [документе DSL](docs/android-mock/03-mock-dsl.md#json-ответ-по-serializable-модели).

`bodyJson` добавляет `Content-Type: application/json; charset=utf-8`, если заголовок не задан явно. JSON превращается в байты до публикации правил.

### Последовательность ответов и задержка

`roundRobin` выдаёт ответы по кругу. Пример сначала вернёт `503`, затем `201`, затем снова `503`:

```kotlin
import dev.androidmock.core.dsl.mockRules
import kotlin.time.Duration.Companion.milliseconds

val orderRules = mockRules {
    rule("create-order", "/v1/orders") {
        match { method("POST") }
        roundRobin {
            response {
                status(503)
                bodyText("try again")
            }
            response {
                status(201)
                delay(250.milliseconds)
                bodyText("created")
            }
        }
    }
}
```

Это позволяет проверить повтор запроса после ошибки. Последовательность не заканчивается и не закрепляет последний ответ: она повторяется по кругу. `engine.resetScenarios()` начинает все последовательности заново. Слот выбирается атомарно и считается использованным даже при последующей отмене запроса; порядок доставки параллельных ответов не гарантируется.

У каждого правила свой счётчик. Если несколько запросов должны продвигать один сценарий, задайте общий ключ. Добавьте импорт `dev.androidmock.core.rule.ScenarioKey`, а внутри `mockRules` опишите:

```kotlin
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
        response { bodyText("write-first") }
        response { bodyText("write-second") }
    }
}
```

Для `GET → POST → GET` общий счётчик выберет слоты `0 → 1 → 2`: ответы будут `read-first → write-second → read-first`. Каждое правило берёт элемент из своего списка по общему номеру. Другой движок с тем же именем ключа имеет независимый счётчик.

Задержку выполняет адаптер: OkHttp блокирует поток вызова, Ktor использует отменяемую coroutine `delay`. Прямой `engine.decide` не ждёт. Задержка должна быть конечной и неотрицательной.

### Ответ, зависящий от запроса

`respondWith` получает снимок запроса и контекст с ID выбранного правила. Используйте его, если один ответ нужно вычислять для разных параметров:

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

`/v1/price?currency=EUR` вернёт `rule=price; currency=EUR`, запрос без параметра — `rule=price; currency=USD`. Функции `matching` и `respondWith` выполняются синхронно и могут вызываться одновременно: они должны быть быстрыми, потокобезопасными и без I/O. Каждый вычисленный ответ проверяется по контракту и лимиту перед возвратом клиенту.

## Тела ответов из Android assets

Правила храните в `src/debug/kotlin`, файлы ответов — например, в `src/debug/assets/mock/bodies`. Вызов `bodyAsset("receipt.json")` получает байты через переданный приложением `BodyAssetResolver`:

```kotlin
import dev.androidmock.core.dsl.BodyAssetResolver
import dev.androidmock.core.dsl.mockRules

// resolver читает файл из assets и ограничивает размер прочитанных данных.
fun receiptRules(resolver: BodyAssetResolver) = mockRules(bodyAssets = resolver) {
    rule("receipt", "/v1/receipt") {
        match { method("GET") }
        respond {
            header("Content-Type", "application/json")
            bodyAsset("receipt.json")
        }
    }
}
```

Готовая Android-реализация resolver есть в [DebugMocks.kt](sample/src/debug/kotlin/dev/androidmock/sample/DebugMocks.kt). Она открывает файл внутри `mock/bodies`, читает его с лимитом и закрывает поток. Построение правил с assets выполняйте вне главного потока. После публикации ответы хранятся в памяти; новые запросы не читают файл повторно.

Такой resolver можно создать в debug-коде приложения и передать в `receiptRules`:

```kotlin
import android.content.Context
import dev.androidmock.core.dsl.BodyAssetResolver
import java.io.ByteArrayOutputStream

fun assetResolver(context: Context, maxBytes: Int = 1_048_576): BodyAssetResolver {
    require(maxBytes > 0)
    return BodyAssetResolver { path ->
        context.assets.open("mock/bodies/$path").use { input ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(count <= maxBytes - output.size()) { "Mock asset too large" }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
    }
}
```

Например, положите `{"orderId":42}` в `src/debug/assets/mock/bodies/receipt.json`, затем выполните `engine.replaceRules(receiptRules(assetResolver(context)))` вне главного потока. Запрос `GET /v1/receipt` получит это тело.

DSL отклоняет абсолютные пути, `.` и `..`, пустые сегменты и обратную косую черту. Отсутствующий файл или ошибка чтения дают `ConfigurationException`. В JVM-тестах resolver может брать байты из ресурсов или памяти. Assets содержат тела ответов; JSON-манифесты правил и исполнение `.kts` не поддерживаются.

## Обновление правил

Сохраняйте экземпляр `RuleEngine`, переданный клиентам, и меняйте его правила через публичные операции:

```kotlin
import dev.androidmock.core.dsl.mockRules
import dev.androidmock.core.engine.RuleEngine
import dev.androidmock.core.rule.RuleId

fun updateUsers(engine: RuleEngine) {
    engine.replaceRules(mockRules {
        rule("users", "/v1/users") { respond { bodyText("first version") } }
    })

    val updatedRule = mockRules {
        rule("users", "/v1/users") { respond { bodyText("second version") } }
    }.single()
    engine.upsertRule(updatedRule)
    // Теперь уже созданные клиенты получают second version.

    engine.resetScenarios()
    engine.removeRule(RuleId("users"))
    // Без другого подходящего правила следующий запрос завершится NoMatchingRule.
}
```

`replaceRules` пригодится для переключения всего набора моков, `upsertRule` — для изменения одного ответа во время работы. Также можно добавить новое правило: передайте `upsertRule` правило с новым ID. `removeRule` убирает отдельный мок, а `resetScenarios` позволяет заново пройти циклический сценарий.

| Операция | Результат |
| --- | --- |
| `replaceRules(rules)` | Заменяет весь набор и сбрасывает все сценарии. Пустой список оставляет клиент без совпадающих правил. |
| `upsertRule(rule)` | Заменяет правило с тем же ID, сохраняя его место в порядке регистрации; новый ID добавляет в конец. Сбрасывает затронутые сценарные ключи. |
| `removeRule(id)` | Удаляет правило и сбрасывает его сценарный ключ. Возвращает `false`, если ID не найден. |
| `resetScenarios()` | Сбрасывает все счётчики, сохраняя правила. |

Для общего ключа `Shared` сброс затрагивает все правила с этим ключом. Обновление публикуется атомарно: уже начатый запрос может завершиться по старому набору, а следующий увидит новый. Ошибка построения или проверки сохраняет прежние правила и счётчики. Контракт конкурентных обновлений описан в [архитектуре ядра](docs/android-mock/02-core-architecture.md#сценарии-и-состояние).

## Ограничения и ошибки

Сопоставление тела запроса работает по байтам, без семантического сравнения JSON. OkHttp по умолчанию не захватывает тело. Для небольшого повторяемого тела включите `RequestBodyCapture.REPEATABLE` в `MockInterceptor`; одноразовые, duplex-тела и тела неизвестной длины не читаются. Ktor предоставляет байты `OutgoingContent.ByteArrayContent`, а потоковые каналы не читает. Если тело недоступно или превышает лимит, условия тела не совпадут, но правило без этих условий может сработать.

Пример OkHttp-клиента с захватом тела для условий `bodyExact...` и `bodyContains...`:

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

Захват вызывает `RequestBody.writeTo`, поэтому включайте его только для тел, чьё повторное создание безопасно.

| Лимит | По умолчанию | Настройка |
| --- | --- | --- |
| Тело одного ответа | 1 MiB | `EngineLimits.maxResponseBytes` |
| Сумма заранее заданных тел в таблице | 16 MiB | `EngineLimits.maxTableBytes` |
| Захватываемое тело запроса | 1 MiB | `maxRequestBytes` в `MockInterceptor` или `mockKtorEngine` |

`mockRules` проверяет набор с обычными `EngineLimits()`, поэтому увеличение лимитов целевого движка не позволяет строить через DSL ответы больше этих значений. Для больших ответов создавайте `Rule` напрямую. Вычисляемый ответ проверяется по лимиту одного ответа при каждом `decide`.

Ошибки построения и публикации дают `ConfigurationException`. Ошибки запроса содержат `MockFailure`: `InvalidRequest`, `NoMatchingRule`, `CallbackFailure` или `InvalidResponse`. В OkHttp они приходят как `MockIOException`, в Ktor — как `KtorMockException`; у обоих исключений есть поле `failure`. HTTP-статус из правила остаётся обычным ответом; при `expectSuccess = true` Ktor дополнительно применяет собственную проверку статуса. Отмена сохраняет поведение соответствующего клиента.

Например, промах можно отличить от других ошибок вызова:

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

В Ktor аналогично проверяется `KtorMockException.failure`. При ошибке повторной загрузки правил сохраните прежний движок и клиент: неудачная публикация их не меняет. При первом запуске ошибка конфигурации должна остановить создание клиента с неполным набором.

В проекте нет глобального перехвата, пропуска несовпавшего запроса в сеть, записи трафика и replay, потоковых мок-ответов, WebSocket/gRPC, шаблонов путей и семантического JSON-матчинга. KMP-публикация пока не настроена. Полный контракт URL, query, тел и ошибок приведён в [архитектуре ядра](docs/android-mock/02-core-architecture.md).

## Сборка и проверки

| Компонент | Версия или настройка в репозитории |
| --- | --- |
| JDK / Gradle Wrapper | Toolchain 17 / 8.13 |
| Kotlin / serialization plugin | 2.3.20 / 2.3.20 |
| Serialization runtime | 1.11.0 |
| Android Gradle Plugin | 8.13.2 |
| `sample` | `minSdk = 23`, `compileSdk = 36`, `targetSdk = 36` |
| OkHttp / Ktor Client | 4.12.0 / 3.0.3 |
| Retrofit в интеграционном тесте | 3.0.0 |

Из корня репозитория:

```shell
./gradlew :mock-core:test :mock-okhttp:test :mock-ktor:test
./gradlew :sample:assembleDebug :sample:lintDebug
./gradlew :sample:assembleDebugAndroidTest
```

Полная сборка с JVM-тестами запускается через `./gradlew build`. Для `sample` нужен Android SDK. На подключённом устройстве или эмуляторе тест assets запускается отдельно:

```shell
./gradlew :sample:connectedDebugAndroidTest
```

В [sample](sample/src/debug/kotlin/dev/androidmock/sample/DebugMocks.kt) один движок подключён к OkHttp и Ktor. Экрана выбора сценариев в нём нет. [PackagedAssetTest.kt](sample/src/androidTest/kotlin/dev/androidmock/sample/PackagedAssetTest.kt) проверяет чтение файла из APK через `targetContext.assets` и отсутствие повторного чтения при `decide`.

По ранее зафиксированным результатам `./gradlew build :sample:assembleDebugAndroidTest` прошли 46 JVM-тестов, собраны debug/release APK и debug test APK, `lintDebug` завершился без ошибок. Тест assets ранее прошёл на Android 36 AVD (`Medium_Phone`, 1 тест); после обновления toolchain его не запускали повторно. Другие Android API levels и сборка реального приложения-потребителя ещё не проверены.

Порядок разработки и отложенные задачи описаны в [плане проекта](docs/android-mock/01-project-plan.md).
