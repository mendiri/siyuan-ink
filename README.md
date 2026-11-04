# SiYuan Ink

App Android para tablets **Boox** que une la interfaz de [SiYuan](https://github.com/siyuan-note/siyuan)
(servida desde tu propio servidor) con un **lienzo de escritura nativo** que usa el SDK del lápiz de Onyx,
con la misma fluidez que la app Notas de Boox.

## Qué hace

- **SiYuan completo**: carga tu servidor SiYuan (p. ej. Docker en una Raspberry Pi) en una vista web adaptada a e-ink.
- **✎ Tinta**: abre un lienzo a pantalla completa. Al guardar, el dibujo se sube a SiYuan como SVG y se inserta como bloque
  debajo del cursor. Los trazos originales se guardan en un JSON, así que **tocar la tinta la vuelve a abrir para editarla**.
- **Reconocimiento de escritura** (ML Kit, en el dispositivo): el texto reconocido se guarda como texto alternativo de la
  imagen y en el atributo `custom-ink-text`, para que las notas manuscritas aparezcan en las búsquedas.
- **✍ Escribir → texto**: escribes a mano, revisas/corriges el texto reconocido y se inserta donde estaba el cursor.
- **Anotar PDFs**: con un PDF abierto en SiYuan, **✎ Tinta** permite escribir encima de las páginas visibles.
  Las anotaciones se guardan en `data/storage/siyuan-ink/pdf/<nombre-del-pdf>.json` (se sincronizan con SiYuan)
  y se vuelven a dibujar sobre el PDF, también al hacer zoom. Con el plugin instalado también se ven en el SiYuan del PC.
- **Plugin para el PC** (`app/src/main/assets/siyuan-plugin/`): dibuja en el SiYuan del PC las anotaciones de PDF
  en la misma posición. Se instala desde la app (⚙ → *Instalar/actualizar plugin*) y se activa en SiYuan →
  Ajustes → Bazar → Descargados → Plugins.
- Goma del lápiz (botón/extremo trasero) y modo goma, deshacer, tres grosores.

## Instalación

1. Descarga `SiYuanInk.apk` de la release **latest** del repositorio (se compila sola con cada cambio).
2. Instálala en la Boox (permite “orígenes desconocidos” si te lo pide).
3. Ábrela e introduce la dirección del servidor, p. ej. `http://192.168.1.50:6806`
   (desde fuera de casa funciona igual con la VPN WireGuard activada).
4. Si SiYuan tiene código de acceso, la propia página lo pedirá la primera vez.
5. La primera vez necesita internet para descargar el modelo de reconocimiento de español (~20 MB).

### Ajustes recomendados en la Boox

Mantén pulsado el icono de *SiYuan Ink* → **Optimizar**: modo de refresco *Equilibrado* o *Rápido* para la navegación.
El lienzo de escritura no depende de ese ajuste: usa el modo de escritura nativo del lápiz.

## Cómo se guarda en SiYuan

```
![texto reconocido](assets/ink-1730000000000-20251103...svg)
{: custom-ink="assets/ink-1730000000000-....json" custom-ink-text="texto reconocido"}
```

Al editar una tinta se suben archivos nuevos; los antiguos quedan como recursos sin usar
(se limpian en SiYuan → Ajustes → Recursos → *Limpiar recursos no utilizados*).

## Compilar

Lo hace GitHub Actions (`.github/workflows/build.yml`). En local: `./gradlew assembleDebug` con el SDK de Android instalado.

La clave de firma `app/debug.keystore` es una clave de depuración incluida a propósito para que cada APK pueda instalarse
encima del anterior. Mantén el repositorio privado si no quieres que nadie más pueda firmar actualizaciones.
