# Платформа и зависимости

- **Minecraft Java 1.21.1 и Java 21.** Выбрана фиксированная ветка, в которой проверены и клиент, и серверные тесты. Minecraft загружается средством сборки и в архивах Naval Engineering не распространяется. [Описание Minecraft 1.21.1](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-1) · [Рекомендации Fabric для 1.21.1](https://fabricmc.net/2024/05/31/121.html)
- **Fabric Loader 0.20.1, Fabric API 0.116.18+1.21.1, Fabric Loom 1.8.13.** Loader, API и Loom опубликованы под Apache 2.0. [Loader](https://github.com/FabricMC/fabric-loader) · [API](https://github.com/FabricMC/fabric) · [Loom](https://github.com/FabricMC/fabric-loom)
- **Yarn 1.21.1+build.3.** Имена игровых интерфейсов под CC0, используются при сборке. [Yarn](https://github.com/FabricMC/yarn)
- **JOML 1.10.5.** Используется штатная библиотека математики Minecraft под MIT. Тестовый модуль получает её через Maven Central, релизный JAR её не дублирует. Интегратор специального назначения учитывает силы Архимеда, изменяющуюся массу при затоплении и геометрию вокселей. [Лицензия JOML](https://github.com/JOML-CI/JOML/blob/1.10.5/LICENSE)
- **Gradle 8.10.2.** Скрипты и JAR Gradle Wrapper распространяются под Apache 2.0. В конфигурации указан SHA-256 дистрибутива. [Gradle](https://docs.gradle.org/8.10.2/release-notes.html) · [Контрольные суммы](https://gradle.org/release-checksums/)
- **JUnit Jupiter 5.11.4.** Применяется при проверке вычислительного модуля, лицензия EPL-2.0. [Лицензия JUnit](https://github.com/junit-team/junit5/blob/r5.11.4/LICENSE.md)

Текстуры экипировки, пиктограмма и звуковые дорожки созданы для проекта. Библиотеки генерации ресурсов используются только скриптами в `tools`.

