# Conectar la mochila privada a Firebase

La configuración se realiza una sola vez desde una cuenta de Google. Solo se necesita **un proyecto Firebase** para los dos móviles. SalvaPack no pide registro, correo, usuario ni contraseña: cada instalación recibe automáticamente una identidad anónima interna.

## 1. Crear el proyecto

Entra en la [consola de Firebase](https://console.firebase.google.com/), pulsa **Crear un proyecto** y llámalo, por ejemplo, `SalvaPack`. Google Analytics es opcional y la app no lo utiliza.

Puedes empezar con el plan Spark y sus cuotas. La app guarda las fotos comprimidas en Firestore y no utiliza Cloud Storage. Las cuotas y precios pueden cambiar, por lo que conviene revisar [los precios de Firebase](https://firebase.google.com/pricing) y [los límites de Firestore](https://firebase.google.com/docs/firestore/quotas).

## 2. Registrar la app Android

En el proyecto, añade una aplicación Android con estos datos:

| Campo | Valor |
| --- | --- |
| Nombre del paquete | `es.salvapack.app` |
| Apodo | `SalvaPack` |
| SHA-1 | No hace falta |

Descarga `google-services.json` y colócalo en `app/google-services.json`. El archivo contiene la dirección e identificadores del proyecto; no contiene tu contraseña ni una clave privada de administrador.

## 3. Activar el acceso anónimo

En **Authentication → Método de acceso / Sign-in method**, activa **Anónimo / Anonymous**. No actives correo y contraseña: la app no los utiliza. Esta identidad técnica se crea por detrás y no muestra ninguna pantalla de cuenta. Consulta la [documentación oficial del acceso anónimo](https://firebase.google.com/docs/auth/android/anonymous-auth).

## 4. Crear Firestore

En **Firestore Database**, crea una base de datos Standard con identificador `(default)`, en modo nativo y en una ubicación europea apropiada. Elige **modo producción**; no uses reglas abiertas de prueba.

No crees manualmente documentos ni colecciones. El primer móvil creará la mochila y su código privado.

## 5. Publicar las reglas

En **Firestore → Reglas**, sustituye el contenido por el archivo [`firebase/firestore.rules`](../firebase/firestore.rules) y pulsa **Publicar**.

En **Firestore → Índices → Campo único**, añade una exención para el grupo `products`, campo `photoBase64`, desactivando sus índices. También puedes publicar reglas e índices desde PowerShell:

```powershell
npm --prefix firebase ci
.\firebase\node_modules\.bin\firebase.cmd login
.\firebase\node_modules\.bin\firebase.cmd deploy --only firestore --project TU_ID_DE_PROYECTO
```

Las reglas impiden listar mochilas o leer perfiles de otros dispositivos. El código aleatorio de 32 caracteres actúa como llave privada: quien no lo conoce no puede vincularse al inventario. Por ello debéis compartirlo únicamente entre vosotros y conservarlo para poder recuperar la mochila al cambiar de móvil.

## 6. Compilar e instalar

Compila desde Android Studio o con:

```powershell
.\gradlew.bat :app:assembleDebug
```

Instala `app/build/outputs/apk/debug/app-debug.apk` en ambos móviles. En el primero pulsa **Crear nuestra mochila**. En el segundo pulsa **Unirme con un código** e introduce el código mostrado por el primero. No aparecerá ninguna pantalla de registro.

Los productos creados previamente en modo local no se importan automáticamente a la mochila compartida.

## Si algo falla

| Síntoma | Qué revisar |
| --- | --- |
| Sigue apareciendo el modo local | Comprueba que existe `app/google-services.json`, recompila e instala el APK nuevo. |
| No termina de preparar el móvil | Activa el proveedor **Anónimo** en Authentication y comprueba Internet. |
| `PERMISSION_DENIED` | Publica las reglas incluidas y verifica que el APK y las reglas correspondan al mismo proyecto. |
| No encuentra la mochila | Copia los 32 caracteres completos y comprueba que ambos APK usan el mismo proyecto Firebase. |
| No llegan cambios | Abre la app con conexión en ambos móviles y revisa el estado de sincronización. |
| No llega un aviso | Revisa el permiso de notificaciones y las restricciones de batería. Android puede retrasar los avisos. |

No se ha desplegado ningún servicio ni utilizado credenciales reales al preparar este proyecto. Los pasos de la consola requieren que accedas tú con tu cuenta de Google.
