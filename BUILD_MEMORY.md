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
