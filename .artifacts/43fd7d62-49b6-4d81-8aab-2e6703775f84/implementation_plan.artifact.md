# Offline Maps Implementation Plan (Mapsforge Vector Maps)

Implement offline vector map support using Mapsforge (`osmdroid-mapsforge`) so users can load country `.map` files for efficient, offline, high-performance rendering.

## User Review Required

> [!NOTE]
> Users will be able to select and load `.map` files (country vector map files downloaded from Mapsforge/OpenAndroMaps) into the app for 100% offline usage.

## Proposed Changes

### [app]

#### [MODIFY] [MainActivity.kt](file:///Users/benard/Apps/AndroidStudioProjects/AreaScopeMapper/app/src/main/java/com/benasafrique/areascopemapper/MainActivity.kt)
- Add file picker support for `.map` files (Mapsforge vector map files).
- Implement Mapsforge tile source configuration (`MapsforgeTileSource`) on OSMDroid `MapView`.
- Handle fallback between online Mapnik tiles and offline `.map` files.

## Verification Plan

### Automated Tests
- Build verification via Gradle (`app:assembleDebug`).

### Manual Verification
- Test loading a country `.map` file via the import picker and verifying offline rendering on the map view.
