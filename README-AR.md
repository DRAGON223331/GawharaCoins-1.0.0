# GawharaCoins 1.0.0 — GawharaSMP

عملة مستقلة باسم **Gawhara Coins (GC)**. لا تعدّل رصيد Vault ولا تحتاج إلى Vault. تحفظ الأرصدة في `plugins/GawharaCoins/balances.yml`.

## أوامر
- `/coins` أو `/gc`: عرض الرصيد.
- `/coins pay <player> <amount>`: تحويل GC للاعب آخر.
- `/coins top`: ترتيب أعلى الأرصدة.
- `/coins shop`: فتح متجر GC داخل Inventory GUI.
- `/coins give <player> <amount>` و`/coins take ...` و`/coins set ...`: للإدارة.
- `/coins reload`: إعادة تحميل الإعدادات.

## أسعار متجر GC
تُعدل المنتجات من `config.yml` داخل `shop.items`. لكل منتج `material`, `name`, `slot`, `amount`, `price`, ويمكن إضافة `lore`. المتجر يعمل من `/coins shop`.

## API العامة للبلجنات الأخرى
الدوال العامة في Main class `com.gawharasmp.coins.GawharaCoins`:
- `long getBalance(UUID playerId)`
- `boolean hasCoins(UUID playerId, long amount)`
- `boolean depositCoins(UUID playerId, long amount, String reason)`
- `boolean withdrawCoins(UUID playerId, long amount, String reason)`
- `boolean awardCoinsOnce(UUID playerId, long amount, String reason, String transactionKey)` — آمنة ضد تكرار مكافأة المهمة/الفعالية بعد إعادة التشغيل.

الإضافات المتكاملة تستدعي API عبر Bukkit PluginManager reflection حتى لا تحتاج تضمين نسخة API داخل كل JAR. لا تحذف `balances.yml` عند التحديث.

## البناء
Paper 26.3 build 170-beta + Java 25. ارفع المشروع إلى GitHub ثم Actions → Build GawharaCoins JAR؛ الـ Artifact الناتج اسمه `GawharaCoins-JAR`.
