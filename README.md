# SPMega

Клиентский мод Fabric с банковым UI, интеграцией с API `spworlds.ru` и локальным SQLite-кэшем.

## Что реализовано

- Открытие меню по `P` и через кнопку `SPMega` в меню `Esc`
- Экран `Карты`:
    - список карт из локальной БД
    - удаление карты
    - обновление данных карты через API
    - добавление карты из конфига (`token.cardId` + `token.cardToken`)
- Экран `Оплата`:
    - перевод по номеру карты
    - при вводе ника: загрузка карт игрока из API и выбор карты получателя
    - выполнение транзакции через API
- Локальная БД `config/spmega.db`:
    - `cards` (id, token, number, name, balance, owner_uuid)
    - `transfer_history` (локальная история переводов)
- Автообновление балансов при входе на сервер

## Ключевые файлы

- `src/main/java/git/yawaflua/tech/spmega/api/SPWorldsApiClient.java`
- `src/client/java/git/yawaflua/tech/spmega/client/ui/service/BankUiService.java`
- `src/client/java/git/yawaflua/tech/spmega/client/ui/service/BankDatabase.java`
- `src/client/java/git/yawaflua/tech/spmega/client/ui/PaymentScreen.java`
- `src/client/java/git/yawaflua/tech/spmega/client/ui/CardScreen.java`

## Конфиг

Файл: `config/spmega.properties`

- `api.domain=https://spworlds.ru`
- `token.cardId=<UUID карты>`
- `token.cardToken=<токен карты>`

При добавлении новой карты через UI выполняется проверка владельца через `GET /api/public/accounts/me`.
Если UUID не совпадает с UUID игрока, показывается сообщение:
`Вы не владелец карты. Часть функций может быть ограничена.`

## Проверка сборки (PowerShell)

```powershell
$javaHome = 'C:/Users/yawaflua/AppData/Roaming/PrismLauncher/java/java-runtime-delta/'
$env:JAVA_HOME = $javaHome
$env:Path = "$($env:JAVA_HOME)bin;$env:Path"
Set-Location 'C:\Users\yawaflua\IdeaProjects\untitled'
.\gradlew.bat classes
```
