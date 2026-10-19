# Gravador Smart — preparação Google Play

## Implementado na branch fix/v3.1
- [x] Compilação configurada com compileSdk/targetSdk 36.
- [x] Workflow de APK debug e AAB release **não assinado**.
- [x] Política de privacidade inicial (exige dados de contato e URL pública).
- [x] Descrição da loja, roteiro de capturas e checklist de testes.

## Antes do lançamento
- [ ] Confirmar nome jurídico do desenvolvedor, e-mail e URL pública da política.
- [ ] Registrar e verificar conta de desenvolvedor na Play Console; atender aos requisitos de teste fechado, se aplicáveis.
- [ ] Definir applicationId exclusivo DEFINITIVO antes da primeira publicação. Atualizar de com.example.voicevault e verificar impacto sobre atualizações/arquivos.
- [ ] Criar chave de upload de forma segura; guardar cópias e senhas fora do repositório.
- [ ] Configurar assinatura release por segredos protegidos no GitHub Actions OU assinar localmente; **nunca commitar .jks, keystore.properties ou senhas**.
- [ ] Conferir ícone de alta resolução, feature graphic e capturas REAIS do APK instalado.
- [ ] Validar formulário Segurança dos dados e declarações de permissões/público-alvo na Play Console.
- [ ] Testar Android 8, 10, 13, 14, 15 e 16 em aparelhos reais ou emuladores.

## Testes funcionais manuais obrigatórios
1. Negar e conceder microfone; confirmar mensagens e ausência de crash.
2. Gravar 30 s, pausar 10 s, retomar 20 s, parar; conferir duração próxima de 50 s e áudio válido.
3. Pausar e retomar também pela notificação com o aplicativo minimizado.
4. Gravar com tela apagada e verificar gravação, notificação e arquivo final.
5. Mudar formato e qualidade; reproduzir gravações antigas e novas.
6. Criar/renomear/colorir/excluir pastas; conferir integridade das gravações.
7. Compartilhar, exportar e reproduzir arquivo exportado em apps externos.
8. Escolher foto de perfil, reiniciar app, conferir persistência; editar nome e reiniciar.
9. Rotacionar, mudar tamanho de fonte/acessibilidade, testar tela pequena e tablet.
10. Checar restauração de backup e remoção de dados sensíveis.

## Nota sobre testes automatizados
O GitHub Actions confirma compilação e empacotamento. Não comprova comportamento de gravação com microfone, pausas reais, galeria ou layout em aparelho.
