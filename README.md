# Hand Tracking

App Android que usa a câmera do celular para rastrear as mãos em tempo real e desenhar o esqueleto (21 pontos azuis ligados por linhas brancas). Feito com Kotlin, CameraX e MediaPipe Hand Landmarker.

## Como baixar o APK

1. Abra a aba **Actions** deste repositório.
2. Clique na execução mais recente de **Build APK**.
3. Em **Artifacts**, baixe `hand-tracking-apk`, extraia o zip e instale o `app-debug.apk` no celular (pode ser preciso permitir a instalação de apps de fontes desconhecidas).

## Como usar

- Aceite a permissão da câmera.
- Mostre a mão para a câmera.
- O botão **Virar câmera** alterna entre frontal e traseira.

## Compilar no seu computador

Abra a pasta no Android Studio e clique em *Build > Build APK*. O modelo `hand_landmarker.task` é baixado automaticamente na primeira compilação.
