# Assinatura para a Google Play

O GitHub Actions gera o bundle **não assinado** para validar a configuração. Ele NÃO deve ser enviado diretamente à Play Console.

## Assinatura segura
1. Criar a chave de upload localmente, guardar a senha no gerenciador de senhas e realizar backup seguro.
2. Ativar Play App Signing na Play Console.
3. Assinar o AAB de release em ambiente controlado ou em workflow separado com secrets e aprovação manual.
4. Não colocar chaves, senhas, tokens ou dados pessoais nos arquivos do GitHub.
5. Validar a assinatura e o applicationId antes do upload.

A assinatura por CI será configurada apenas quando a chave de upload e o método seguro de fornecimento estiverem definidos, sem compartilhar a chave nesta conversa.
