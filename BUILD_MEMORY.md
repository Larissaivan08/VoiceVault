# 🧠 BUILD_MEMORY — VoiceVault

Memória de build: erros encontrados e soluções aplicadas ao compilar o VoiceVault.
**Na próxima vez, siga esta lista antes/depois de rodar `./gradlew assembleDebug` para corrigir tudo preventivamente.**

## Erros já resolvidos (aplique estas correções desde o início)

1. **SDK/NDK ausente no ambiente**
   - Instalar cmdline-tools + platform 34 + build-tools via `sdkmanager`, aceitar licenças (`yes | sdkmanager --licenses`).
   - Criar `local.properties` apontando para o SDK: `sdk.dir=/opt/android-sdk`.

2. **Gradle / AGP incompatíveis com o JDK instalado**
   - Usar AGP 8.x + Gradle 8.x com JDK 17. Se o JDK for 21, subir a versão do Gradle ou usar toolchain Java 17 no `build.gradle.kts`.

3. **Dependências não declaradas (Material 3, Room, KSP)**
   - Adicionar no `app/build.gradle.kts`:
     - `com.google.android.material:material` (tema Material3)
     - `androidx.room:room-runtime` + `room-ktx` + plugin KSP `androidx.room:room-compiler`
     - `androidx.lifecycle`, `androidx.activity-ktx`, `androidx.core-ktx`
   - Plugins KSP devem ter versão compatível com a versão do Kotlin usada.

4. **Referências de recursos quebradas (layouts/themes)**
   - Conferir que todo `@id/@string/@color/@drawable` referenciado existe em `res/`.
   - Tema deve herdar de `Theme.Material3.DayNight` e o manifest usar esse tema.

5. **Permissões/minSdk**
   - Declarar apenas `RECORD_AUDIO` no manifest; minSdk 26+ evita request dinâmico de storage.

6. **Assinatura debug**
   - APK de debug é assinado automaticamente pela chave padrão (`~/.android/debug.keystore`); não adicionar signingConfig próprio.

## Checklist de build limpo
```bash
cd VoiceVault
./gradlew clean assembleDebug --stacktrace
# APK final: app/build/outputs/apk/debug/app-debug.apk (~6 MB)
```

Se algum erro novo aparecer, anexe aqui neste arquivo (data + erro + correção).


## v2 (out/2026) — novas features: pastas laterais, renomear/excluir pasta, formatos, segundo plano
Erros encontrados e corrigidos nesta versão:

1. **`Returns are not allowed for functions with expression body`** (Format.kt)
   - Função `= try {...}` não pode usar `return` dentro. Solução: usar `runCatching { ... }.getOrDefault(false)` e `error(...)` para abortar.
2. **`Unresolved reference: MP3`** em `MediaRecorder.OutputFormat.MP3` / `AudioEncoder.MP3`
   - O AOSP (compileSdk 34) NÃO tem essas constantes públicas; só alguns fabricantes expõem.
   - Solução: buscar a constante por reflection (`Class.getField("MP3")`) e falhar graciosamente com mensagem "aparelho não tem encoder MP3 nativo".
3. **`'outPath' hides member of supertype 'State' and needs 'override' modifier`** (LastRecord.Fail)
   - Propriedade que já existe no sealed class pai precisa de `override`.
4. **Gradle daemon morto por falta de RAM** ("daemon disappeared unexpectedly")
   - Ambiente com ~2 GB: compilar com `--no-daemon` e `GRADLE_OPTS=-Xmx1024m`.
5. **Foreground service Android 13+**: exigir permissão em runtime na ordem certa:
   RECORD_AUDIO -> POST_NOTIFICATIONS -> startForegroundService com type=microphone.
