# Liferay Remote Deployer — IntelliJ Plugin

Deploy artifacts (`.jar` / `.war`) a servidores Liferay remotos vía SSH directamente desde IntelliJ.

## Características

- Deploy a múltiples servidores en modo **sequential** (rolling) o **parallel**
- Limpieza automática de caché OSGi y Tomcat work
- **Tail de logs** en vivo tras cada deploy
- Soporte de entornos con código de colores
- Autenticación por **contraseña** o **clave SSH**
- Gestión de servidores **NGINX** con soporte de upstream

---

## Instalación desde el repositorio custom

1. En IntelliJ ve a **Settings → Plugins → ⚙ → Manage Plugin Repositories…**
2. Añade la siguiente URL:
   ```
   https://raw.githubusercontent.com/dqtorres/liferay-intellij-deployer/main/updatePlugins.xml
   ```
3. En el buscador de plugins escribe **Liferay Remote Deployer** e instálalo
4. Reinicia IntelliJ

A partir de ahí IntelliJ avisará automáticamente cuando haya una nueva versión disponible.

---

## Desarrollo local

### Requisitos

- Java 21+
- Gradle (incluido via wrapper `./gradlew`)

### Construir el plugin

```bash
./gradlew buildPlugin
```

El zip generado estará en:

```
build/distributions/liferay-intellij-deployer-<version>.zip
```

### Instalar en IntelliJ sin publicar

1. **Settings → Plugins → ⚙ → Install Plugin from Disk…**
2. Selecciona el `.zip` de `build/distributions/`
3. Reinicia IntelliJ

---

## Publicar una nueva versión

### 1. Actualizar la versión

Edita `build.gradle.kts` y cambia el número de versión:

```kotlin
version = "1.1.0"
```

### 2. Hacer commit y crear el tag

```bash
git add build.gradle.kts
git commit -m "chore: bump version to 1.1.0"
git tag v1.1.0
git push origin main
git push origin v1.1.0
```

### 3. El workflow hace el resto automáticamente

GitHub Actions (`release.yml`) se dispara con el tag y:

1. Compila el plugin con `./gradlew buildPlugin`
2. Crea una **GitHub Release** con el `.zip` adjunto
3. Actualiza `updatePlugins.xml` en `main` con la nueva URL y versión

Los usuarios con el repositorio configurado en IntelliJ recibirán la notificación de actualización automáticamente.
