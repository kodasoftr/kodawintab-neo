
# KodaWinTab Neo

**KodaWinTab Neo** é um projeto Android desenvolvido para auxiliar na utilização e configuração de mesas digitalizadoras conectadas a dispositivos Android através de USB OTG.

O objetivo é permitir que usuários utilizem mesas digitalizadoras compatíveis em celulares e tablets Android, oferecendo uma camada de comunicação e configuração entre o hardware e o sistema.

> **Importante:** devido às limitações da arquitetura Android, o KodaWinTab Neo não é um driver totalmente nativo de sistema. Seu funcionamento depende das APIs, permissões e recursos disponibilizados pelo Android.

---

## 1. O que é o KodaWinTab Neo?

O KodaWinTab Neo é um projeto que busca facilitar a conexão e utilização de mesas digitalizadoras USB em dispositivos Android.

O projeto trabalha com a comunicação entre:

- Mesa digitalizadora USB.
- Sistema Android.
- Aplicativo KodaWinTab Neo.
- Comunicação ADB para configuração e diagnóstico.
- Interface USB OTG.

A compatibilidade depende do modelo da mesa digitalizadora, da versão do Android e do protocolo utilizado pelo fabricante.

## 2. Como funciona a ativação via ADB?

ADB significa **Android Debug Bridge**.

É uma ferramenta oficial do Android que permite estabelecer comunicação entre um computador e um dispositivo Android.

No KodaWinTab Neo, o ADB pode ser utilizado durante a configuração para executar comandos e auxiliar na comunicação com o sistema Android.

O ADB não é o driver da mesa digitalizadora. Ele funciona como uma ponte de comunicação que permite ao computador enviar comandos ao dispositivo.

### Ativando a depuração USB

No celular ou tablet:

1. Abra as configurações do Android.
2. Entre em Sobre o telefone.
3. Localize Número da versão ou Número da compilação.
4. Toque várias vezes até ativar as opções de desenvolvedor.
5. Volte às configurações.
6. Abra Opções do desenvolvedor.
7. Ative Depuração USB.

### Conectando ao computador

1. Instale o Android SDK Platform-Tools.
2. Conecte o celular ao computador usando um cabo USB de dados.
3. Desbloqueie a tela do aparelho.
4. Autorize a conexão ADB quando aparecer a solicitação RSA.

Abra o CMD ou terminal do Windows e execute:

```bat
adb devices
```

Se estiver tudo correto, o aparelho aparecerá na lista como `device`.

Para iniciar o servidor ADB:

```bat
adb start-server
```

Para verificar novamente:

```bat
adb devices
```

### Como o ADB participa do processo?

O fluxo geral funciona assim:

1. O computador estabelece conexão com o Android.
2. O usuário autoriza a depuração USB.
3. O ADB permite enviar comandos ao aparelho.
4. O projeto pode utilizar os recursos de configuração permitidos pelo Android.
5. A mesa digitalizadora é conectada através de USB OTG.
6. O aplicativo realiza as operações disponíveis conforme as permissões concedidas.

**Atenção:** o ADB não concede automaticamente acesso root nem permissão irrestrita ao hardware. Algumas operações dependem de permissões especiais ou privilégios de sistema.

## 3. Conectando a mesa digitalizadora

Para utilizar uma mesa digitalizadora:

1. Verifique se o dispositivo Android oferece suporte a USB OTG.
2. Conecte a mesa utilizando um adaptador OTG compatível.
3. Caso necessário, utilize um hub USB com alimentação externa.
4. Autorize o acesso USB quando solicitado pelo Android.
5. Abra o KodaWinTab Neo e utilize as opções de configuração disponíveis no aplicativo.

Nem todas as mesas digitalizadoras são compatíveis.

Alguns fabricantes utilizam protocolos proprietários que podem exigir implementações específicas.

## 4. Limitações do Android: driver não totalmente nativo

Uma das principais limitações do projeto é a arquitetura do próprio Android.

Diferentemente do Windows e do Linux, aplicativos Android convencionais não possuem liberdade para instalar ou modificar drivers diretamente no kernel do sistema.

O Android utiliza uma arquitetura baseada em camadas de segurança, permissões e interfaces específicas para comunicação com hardware.

Por esse motivo, o KodaWinTab Neo não deve ser considerado um driver de kernel completo.

### O que isso significa na prática?

- Não instala livremente drivers no kernel Android.
- Não substitui automaticamente o driver HID do sistema.
- Não possui acesso irrestrito aos dispositivos USB.
- Depende das permissões disponibilizadas pelo Android.
- Algumas funções podem depender do aplicativo utilizado para desenhar.
- A compatibilidade com pressão e inclinação depende dos dados disponibilizados pelo hardware e pelo sistema.

### Por que não é totalmente nativo?

Para desenvolver um driver verdadeiramente nativo seria necessário trabalhar com componentes de baixo nível do Android, como drivers de kernel, interfaces HID e componentes do sistema.

Essas operações normalmente exigem privilégios especiais, integração com o sistema operacional ou suporte do fabricante.

O KodaWinTab Neo utiliza uma abordagem baseada nas possibilidades disponíveis para aplicativos Android, podendo utilizar ADB como ferramenta auxiliar de configuração.

Isso permite desenvolver uma solução sem exigir que todos os usuários instalem uma ROM personalizada ou modifiquem o kernel do aparelho.

## 5. Como compilar o projeto

O projeto utiliza tecnologias do ecossistema Android:

- Kotlin.
- Gradle Kotlin DSL.
- Android Gradle Plugin.
- Jetpack Compose.
- KSP.
- Google Services.

### Requisitos

- Windows 10 ou superior recomendado.
- JDK compatível com a versão do Gradle utilizada.
- Android SDK.
- Android SDK Platform-Tools.
- Git.
- Visual Studio Code ou outro editor de código.

### Visual Studio tradicional ou Visual Studio Code?

O projeto é baseado em Kotlin e Gradle.

O Visual Studio tradicional não é o ambiente nativo de compilação para projetos Android Kotlin.

É possível editar arquivos utilizando ferramentas da Microsoft, mas a compilação deve ser executada pelo Gradle.

O Visual Studio Code é uma alternativa para editar o projeto e utilizar o terminal integrado.

### Baixando o projeto

Abra o CMD e execute:

```bat
git clone https://github.com/kodasoftr/kodawintab-neo.git
```

Entre na pasta:

```bat
cd kodawintab-neo
```

Abra a pasta no editor de sua preferência.

### Compilando o APK

Na pasta principal do projeto, execute:

```bat
gradlew.bat assembleDebug
```

O Gradle irá baixar as dependências necessárias e iniciar a compilação.

Após a compilação, o APK de depuração normalmente estará em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

O caminho pode variar conforme a configuração dos módulos do projeto.

### Instalando no Android

Com o celular conectado e autorizado pelo ADB:

```bat
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

O parâmetro `-r` permite reinstalar o aplicativo mantendo os dados quando a assinatura e as condições de instalação forem compatíveis.

### Visualizando registros e erros

Para acompanhar os registros do Android:

```bat
adb logcat
```

Essa ferramenta ajuda a identificar problemas durante a inicialização, conexão USB e execução do aplicativo.

## 6. Solução de problemas

| Problema | Possível solução |
|---|---|
| ADB não reconhece o aparelho | Verifique cabo USB, driver e depuração USB. |
| Unauthorized | Autorize a chave RSA na tela do Android. |
| Mesa não reconhecida | Verifique OTG, alimentação e compatibilidade. |
| Gradle não compila | Confira a versão do JDK e do Gradle. |
| Android SDK não encontrado | Configure o caminho do Android SDK. |
| APK não instala | Verifique assinatura e versão do Android. |
| Caneta sem pressão | Verifique o suporte HID e os dados disponibilizados pelo sistema. |

## 7. Segurança

- Utilize ADB somente em computadores confiáveis.
- Não autorize conexões desconhecidas.
- Evite manter a depuração USB ativada sem necessidade.
- Não execute comandos ADB sem conhecer sua finalidade.
- Tenha cuidado ao utilizar comandos que alteram configurações protegidas do Android.

## 8. Compatibilidade

A compatibilidade depende de diversos fatores:

- Modelo da mesa digitalizadora.
- Controlador USB.
- Protocolo HID ou proprietário.
- Versão do Android.
- Suporte USB OTG.
- Permissões disponíveis.
- Aplicativo utilizado para desenho.

Uma mesa compatível com Windows não necessariamente será compatível com Android.

## 9. Contribuições

Contribuições, testes e relatos de problemas são bem-vindos.

Ao relatar um problema, informe:

- Modelo do celular ou tablet.
- Versão do Android.
- Modelo da mesa digitalizadora.
- Adaptador OTG utilizado.
- Resultado do comando `adb devices`.
- Erros encontrados no Logcat.

Não publique números de série, dados pessoais ou informações privadas do dispositivo.

---

**Projeto:** KodaWinTab Neo  
by Gabriel Freitas  kodasoftr

