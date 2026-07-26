# view-on-map — Fase 2: Analisi tecnica della codebase

**Feature:** `view-on-map`
**Data:** 2026-07-26
**Base analizzata:** working tree di `/home2/agoldoni/projects/bike` (3 file Kotlin, nessun test)

---

## Stato attuale del progetto

La codebase è minima e recentissima: tre soli file Kotlin nel package `it.agoldoni.bike`.

| File | Ruolo | Righe |
|---|---|---|
| `app/src/main/java/it/agoldoni/bike/RideTracker.kt` | Stato del giro condiviso (singleton di processo con `StateFlow`) | 51 |
| `app/src/main/java/it/agoldoni/bike/TrackingService.kt` | Foreground service, campionamento GPS a 1 Hz | 172 |
| `app/src/main/java/it/agoldoni/bike/MainActivity.kt` | UI Compose a schermata singola | 168 |

Non esiste alcuna directory di test (`app/src/test` e `app/src/androidTest` sono assenti),
non esistono ViewModel, repository o layer di persistenza. Non c'è dependency injection.
Il flusso dati è unidirezionale e volutamente diretto:

```
  TrackingService  ──(onSample/onElapsed/onSpeedLost)──►  RideTracker (StateFlow<RideState>)
        ▲                                                          │
        │ startForegroundService(ACTION_START|ACTION_STOP)          │ collectAsStateWithLifecycle
        │                                                          ▼
                                MainActivity → RideScreen(state, onStart, onStop)
```

---

## A. File coinvolti

| File | Tipo di modifica | Motivazione |
|---|---|---|
| `gradle/libs.versions.toml` | Modifica | Aggiungere la versione e la libreria della mappa al version catalog. Il progetto usa esclusivamente alias del catalog (nessuna dipendenza hardcoded in `app/build.gradle.kts`), quindi la convenzione va rispettata. |
| `app/build.gradle.kts` | Modifica | Aggiungere l'`implementation` della libreria mappa nel blocco `dependencies` (righe 62-77). |
| `app/src/main/AndroidManifest.xml` | Modifica | Aggiungere `android.permission.INTERNET` (oggi assente: righe 4-8 dichiarano solo posizione, foreground service e notifiche). Il download delle tile senza questo permesso fallisce silenziosamente lasciando la mappa grigia. |
| `app/src/main/java/it/agoldoni/bike/RideTracker.kt` | Modifica | Estendere `RideState` con la posizione corrente e la lista dei punti del tracciato; aggiungere il reset dei punti in `onStart()`. |
| `app/src/main/java/it/agoldoni/bike/TrackingService.kt` | Modifica | In `onLocation()` (righe 88-117) propagare latitudine e longitudine allo stato, oggi scartate dopo il calcolo di velocità e distanza. |
| `app/src/main/java/it/agoldoni/bike/MainActivity.kt` | Modifica sostanziale | Ristrutturare `RideScreen` (righe 97-152) da `Column` a schermo pieno a `Box` con mappa sullo sfondo e pannello dati ancorato in basso. |
| `app/src/main/java/it/agoldoni/bike/RideMap.kt` | **Nuovo** | Composable che incapsula la mappa e il suo ciclo di vita, isolando l'interoperabilità `View`/Compose dal resto della UI. |
| `app/src/main/java/it/agoldoni/bike/RidePanel.kt` | **Nuovo** | Pannello dati inferiore con i due stati compatto/espanso; accoglie i composable `Metric` e `formatElapsed` spostati da `MainActivity.kt` (righe 155-168). |
| `app/src/main/res/values/strings.xml` | Modifica | Nuove stringhe: attesa segnale GPS, descrizione del pulsante di ricentratura, attribuzione OSM. |
| `app/src/test/java/it/agoldoni/bike/RideTrackerTest.kt` | **Nuovo** | Prima suite di unit test del progetto (vedi sezione D). |

### Libreria scelta: osmdroid

| Candidato | Versione più recente su Maven Central (verificata il 2026-07-26) | Valutazione |
|---|---|---|
| `org.osmdroid:osmdroid-android` | **6.1.20** (agosto 2024) | **Scelta.** Tile raster OSM dirette, nessuna API key, caching su disco incluso, API semplice (`MapView`, `Marker`, `Polyline`). Basata su `View`. |
| `org.maplibre.gl:android-sdk` | 11.11.0 | Scartata per la prima versione: tile vettoriali con rendering più fluido, ma richiede un endpoint di style/tile server (non incluso) e aumenta sensibilmente peso e complessità. |

osmdroid è fermo da circa due anni: è un rischio di manutenzione da mettere agli atti,
ma l'API è stabile e il progetto è ampiamente usato. Se in futuro servissero tile
vettoriali o rotazione fluida, la migrazione a MapLibre resta possibile perché tutta
l'interazione con la mappa è confinata nel nuovo `RideMap.kt`.

---

## B. Contratti e interfacce da modificare

### `RideState` — estensione del data class

Stato attuale (`RideTracker.kt` righe 8-16):

```kotlin
data class RideState(
    val isTracking: Boolean = false,
    val speedKmh: Float = 0f,
    val distanceMeters: Float = 0f,
    val elapsedMillis: Long = 0L,
)
```

Va esteso con la posizione corrente e il tracciato. Due campi nuovi:

- `currentPosition: GeoPoint?` — `null` finché non arriva il primo fix valido; alimenta
  sia il marker sia l'indicazione "attesa segnale" della US-004.
- `trackPoints: List<GeoPoint>` — punti accumulati del giro corrente.

Serve un tipo per la coordinata. **Da non usare `org.osmdroid.util.GeoPoint` dentro
`RideState`**: legherebbe il modello di dominio alla libreria di mappa e vanificherebbe
l'isolamento descritto sopra. Va introdotto un piccolo data class del progetto
(`data class GeoPoint(val lat: Double, val lon: Double)`), convertito in quello di
osmdroid solo dentro `RideMap.kt`.

**Attenzione al costo delle copie**: `RideState` è un data class immutabile aggiornato
via `MutableStateFlow.update` a ogni campione. Aggiungere una `List` che cresce di un
elemento al secondo significa copiare una lista sempre più lunga a ogni fix. Su un giro
di due ore sono ~7000 elementi copiati ogni secondo. Soluzioni possibili, da decidere
in Fase 3: esporre il tracciato in un secondo `StateFlow` dedicato, aggiornarlo con una
lista persistente/append-only, o campionare i punti del tracciato più raramente dei
campioni di velocità (es. un punto ogni 5 m).

### Nuovi metodi su `RideTracker`

| Metodo | Firma | Note |
|---|---|---|
| `onSample` | **Modifica**: `onSample(speedKmh: Float, deltaMeters: Float, position: GeoPoint)` | Aggiunta del parametro posizione. Unico chiamante: `TrackingService.kt:116`. |
| `onFirstFix` | **Nuovo**: `onFirstFix(position: GeoPoint)` | Il primo fix oggi esce da `onLocation()` con un `return` anticipato (`TrackingService.kt:94-98`) senza notificare nulla: senza questo metodo il marker non comparirebbe finché non ci si muove di 2 metri. |

### Nessuna modifica a

- Contratti del service (`ACTION_START` / `ACTION_STOP`, `TrackingService.kt:162-163`).
- Schema dati o API: il progetto non ha né database né rete propria.
- Non ci sono breaking change verso l'esterno: l'app non espone API pubbliche.

---

## C. Pattern da rispettare

Rilevati leggendo il codice esistente:

1. **Commenti in italiano, solo per i "perché"**. Il codice esistente commenta le scelte
   non ovvie (soglia anti-jitter in `TrackingService.kt:100-103`, fallback della velocità
   alle righe 107-109) e non descrive mai l'ovvio. Le nuove parti devono seguire lo stesso
   criterio: es. va spiegato *perché* si imposta uno User-Agent osmdroid, non *cosa* fa la riga.
2. **Version catalog obbligatorio**: ogni dipendenza passa da `gradle/libs.versions.toml`
   con alias `libs.*` (`app/build.gradle.kts` righe 62-77). Nessuna dipendenza in chiaro.
3. **Composable privati nello stesso file, prefisso di visibilità esplicito**: `RideScreen`
   e `Metric` sono dichiarati `private fun` (`MainActivity.kt:98` e `:155`). I nuovi
   composable in file separati saranno `internal` o pubblici solo se davvero usati altrove.
4. **Stato osservato con `collectAsStateWithLifecycle`** (`MainActivity.kt:48`), non
   `collectAsState`: da mantenere per non consumare aggiornamenti in background.
5. **Formattazione numerica con `Locale.ITALY`** (`MainActivity.kt:114`, `:127`, `:132`,
   `:163`): la virgola decimale è una scelta deliberata, va conservata nel pannello.
6. **Tema scuro fisso**: `MaterialTheme(colorScheme = darkColorScheme())`
   (`MainActivity.kt:47`) e sfondo nero (`MainActivity.kt:103`). Il pannello dati deve
   restare leggibile sopra la mappa, che è invece chiara: serve un fondo opaco o
   semitrasparente scuro, non trasparenza piena.
7. **Colori inline**: il progetto non ha ancora un file `Color.kt`; i colori sono letterali
   nei composable (`MainActivity.kt:141-142`). Introdurre una palette condivisa è
   accettabile ma va fatto in modo consistente, non a metà.
8. **Gestione permessi centralizzata nell'Activity** (`MainActivity.kt:37-43` e `:72-85`),
   con il service che assume il permesso già concesso (`@SuppressLint("MissingPermission")`,
   `TrackingService.kt:56`). La mappa non deve introdurre un secondo punto di richiesta permessi.

---

## D. Test da creare o aggiornare

**Il progetto non ha attualmente alcun test** e nessuna dipendenza di test attiva oltre a
`junit` 4.13.2, già dichiarata (`gradle/libs.versions.toml`) e già collegata come
`testImplementation` (`app/build.gradle.kts:76`). Le directory `app/src/test` e
`app/src/androidTest` non esistono: vanno create.

| Area | Tipo | Contenuto | File |
|---|---|---|---|
| Stato del giro | Unit | `onStart()` azzera il tracciato di un giro precedente; `onSample()` accoda il punto e incrementa la distanza; `onFirstFix()` popola la posizione senza toccare la distanza; `onSpeedLost()` non altera il tracciato. | `app/src/test/java/it/agoldoni/bike/RideTrackerTest.kt` (nuovo) |
| Conversione coordinate | Unit | Mapping fra il `GeoPoint` di dominio e quello di osmdroid, comprese le coordinate negative (emisfero ovest/sud). | stesso file o `GeoPointMapperTest.kt` (nuovo) |
| Pannello espandibile | UI (Compose) | Tap sul pannello → stato espanso; tap sulla mappa → stato compatto; il pulsante START/STOP resta cliccabile in entrambi gli stati. Richiede `androidx.compose.ui:ui-test-junit4` e `debugImplementation` di `ui-test-manifest`: **nuove dipendenze da aggiungere**. | `app/src/androidTest/java/it/agoldoni/bike/RidePanelTest.kt` (nuovo) |
| Mappa | Manuale | osmdroid non è testabile in modo utile in unit test. Verifica manuale su emulatore con `adb emu geo fix` (procedura già usata e funzionante in questo progetto) e su telefono con un giro reale. | — |

Note sulla verifica manuale, basate su quanto già sperimentato durante lo sviluppo del
tracciamento: l'emulatore `Emulator_x86_64` (API 33, immagine con Play Services) risponde
correttamente a `adb emu geo fix <lon> <lat>`, e il fused provider ripete la stessa
posizione fra un fix e l'altro — il tracciato non deve accumulare punti duplicati.

---

## E. Rischi tecnici aggiornati (con evidenze)

| Rischio | Evidenza nel codice | Impatto | Nota per la Fase 3 |
|---|---|---|---|
| **Copia della lista dei punti a ogni fix** | `RideTracker.kt:45-49`: `onSample` fa `it.copy(...)` su ogni campione, a 1 Hz | Alto su giri lunghi | Decidere la strategia prima di scrivere il codice (vedi sezione B) |
| **Il primo fix non notifica nulla** | `TrackingService.kt:94-98`: `return` anticipato dopo aver solo memorizzato `lastLocation` | Marker assente finché non ci si muove | Serve `onFirstFix()` |
| **Punti scartati dal filtro anti-jitter** | `TrackingService.kt:105`: `if (deltaMeters < MIN_STEP_M) return` | Basso, anzi positivo | Il tracciato eredita gratuitamente il filtro: nessun punto duplicato da fermo |
| **Permesso INTERNET assente** | `AndroidManifest.xml:4-8` | Bloccante | Mappa grigia senza errori evidenti se dimenticato |
| **Ciclo di vita della MapView** | Nessun precedente nel progetto: non c'è alcun uso di `AndroidView` | Medio-alto | `onResume`/`onPause`/`onDetach` della `MapView` vanno agganciati esplicitamente al lifecycle Compose, altrimenti memory leak e mappa congelata al ritorno da background |
| **Conflitto tra gesture della mappa e tap di collasso** | Requisito US-003: tap sulla mappa collassa il pannello | Medio | Il tap di collasso non deve impedire pan e zoom della mappa: va gestito senza consumare i gesti della `MapView` |
| **`FLAG_KEEP_SCREEN_ON` con mappa attiva** | `MainActivity.kt:52-58` tiene lo schermo acceso durante il giro | Medio | Schermo sempre acceso + rendering mappa + GPS: il consumo va misurato su un giro reale (M6) |
| **Stato perso alla morte del processo** | `RideTracker.kt:23` è un `object` senza persistenza | Medio | La feature lo rende più visibile (si perde anche il tracciato disegnato); non risolvibile qui |
| **Manutenzione di osmdroid** | Ultima release 6.1.20 di agosto 2024 | Basso-medio | Isolare l'uso in `RideMap.kt` per rendere sostituibile la libreria |
| **Attribuzione OSM** | — | Legale/policy | L'attribuzione "© OpenStreetMap contributors" è obbligatoria; osmdroid la disegna di default, ma il pannello dati non deve coprirla |

---

## F. Prerequisiti e task bloccanti

1. **Decidere la struttura dati del tracciato** (bloccante per l'implementazione di
   `RideTracker`): campo dentro `RideState`, `StateFlow` separato, o campionamento ridotto.
   È l'unica scelta architetturale che condiziona il resto.
2. **Aggiungere il permesso `INTERNET`** al manifest: prerequisito di qualsiasi verifica
   visiva della mappa.
3. **Creare la struttura di test** `app/src/test/java/it/agoldoni/bike/`, oggi inesistente,
   e aggiungere le dipendenze di Compose UI test se si vuole coprire il pannello.
4. **Definire lo User-Agent osmdroid** prima del primo avvio con tile reali: la
   configurazione va fatta una sola volta all'avvio (`Configuration.getInstance()`),
   usando l'applicationId. Senza, si viola la tile usage policy.
5. **Non è bloccante ma va deciso**: se introdurre un `ViewModel` per la UI. Oggi l'Activity
   osserva direttamente il singleton; con mappa, stato del pannello e ricentratura la
   quantità di stato UI cresce e la scelta attuale inizia a stare stretta.

---

## Riepilogo dell'impatto

- File nuovi: **4** (2 di produzione, 1-2 di test).
- File modificati: **6**.
- Dipendenze nuove: **1** di produzione (osmdroid), **2** opzionali di test.
- Nessun breaking change esterno, nessuna migrazione dati.
- Il grosso del lavoro è concentrato in `MainActivity.kt`, che va ristrutturato da
  layout a colonna singola a mappa + pannello sovrapposto.

---

*Documento generato con la skill `claude-code-feature` — Fase 2.*
