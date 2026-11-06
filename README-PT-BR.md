# GPS Speedometer — projeto Android

**Importante:** este pacote contém o projeto-fonte, não um APK já compilado.

## Recursos implementados nesta primeira versão
- Tela horizontal, preta e em tela cheia.
- Limite da via à esquerda em verde e velocidade GPS à direita em azul.
- Números dimensionados automaticamente para ocupar quase toda a metade da tela.
- Tela sempre ligada.
- Avisos falados em português brasileiro para novo limite, radar, radar de semáforo e lombada.
- Consulta de limites `maxspeed` e pontos cadastrados no OpenStreetMap/Overpass.
- Não inventa limites quando a informação não é encontrada.

## Limitações que precisam ser consideradas
- A cobertura do OpenStreetMap varia por região e pode estar incompleta.
- A identificação de radares de semáforo depende do modo como foram cadastrados no mapa.
- A posição e a direção dos alertas precisam ser validadas em uso real.
- Internet é necessária para consultar dados do mapa; a velocidade GPS funciona sem internet.
- O aplicativo não é um sistema certificado de navegação e não substitui a sinalização da via.

## Como gerar o APK no Android Studio
1. Instale o Android Studio em um computador Windows, Mac ou Linux.
2. Extraia este ZIP para uma pasta.
3. No Android Studio, escolha **Open** e selecione a pasta `GPS-Speedometer`.
4. Aguarde o Gradle sincronizar e baixar os componentes.
5. Selecione **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. O Android Studio indicará onde o APK foi salvo.
7. Transfira o APK para o JOVI V70, instale e permita o acesso à localização.

## Localização e dados
O GPS é usado no aparelho para calcular a velocidade. Para procurar limites e alertas próximos, o aplicativo envia coordenadas ao serviço público Overpass do OpenStreetMap. Não há cadastro ou conta.

## Gerar o APK sem Android Studio (GitHub Actions)
1. Crie uma conta gratuita em github.com e um repositório novo (Add file > Upload files).
2. Envie todo o conteúdo desta pasta (incluindo a pasta oculta `.github`).
3. Abra a aba **Actions**, aguarde o job "Build APK" ficar verde (~5 min).
4. Clique na execução e baixe **GPS-Speedometer-APK** em *Artifacts*. Dentro do zip está o `app-debug.apk`.
