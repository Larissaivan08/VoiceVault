# 🎙️ VoiceVault

Gravador de voz **open-source** (100% original) com a funcionalidade que apps comerciais
costumam não ter: **pastas para organizar suas gravações**.

## ✨ Funcionalidades
- 🎙️ Gravação em M4A (AAC) com cronômetro ao vivo
- 📁 **Pastas ilimitadas** (criar, renomear, excluir) para separar áudios
- 📂 **Mover gravação entre pastas** (menu de contexto / tela do player)
- ▶️ Player embutido com barra de progresso clicável
- ✏️ Renomear e 🗑️ excluir gravações (com confirmação)
- 🔍 Busca por nome
- 💾 Tudo offline — nada sai do aparelho. Sem anúncios, sem licença, sem rastreamento.

## 🛠️ Como compilar (no seu PC)
1. Instale o [Android Studio](https://developer.android.com/studio).
2. Abra esta pasta: `File > Open > VoiceVault`.
3. Aguarde o Gradle sincronizar e clique em **Run ▶** no celular/emulador (Android 8.0+).

Via linha de comando (precisa do Android SDK instalado):
```bash
./gradlew assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk
```

## 🚨 Permissão de microfone (Android 12+)
Para sideload/apps open-source, o Android pode exigir conceder o microfone via *appops*
uma única vez (isso evita reprovação no Play Store por "permissões não relacionadas"):
```bash
adb shell appops set com.example.voicevault RECORD_AUDIO allow
```
Ou: Configurações → Apps → VoiceVault → Permissões → Microfone → Permitir.

## 🧱 Arquitetura
- Kotlin + Views + Material Design 3
- **Room** (SQLite): tabelas `folders` e `recordings`, FK com `ON DELETE CASCADE`
  (excluir uma pasta apaga as gravações dela automaticamente)
- Áudio gravado no armazenamento privado (`filesDir/recordings/<id>.m4a`)
  → nenhuma permissão de armazenamento necessária
- `MediaRecorder` grava, `MediaPlayer` reproduz

## 📄 Licença
MIT — use, estude e modifique à vontade.
