# salva-giro — Fase 2: Analisi tecnica della codebase

**Feature:** `salva-giro`
**Data:** 2026-08-11
**Riferimento:** [phase-1-requirements.md](phase-1-requirements.md)

---

## A. File coinvolti

### Nuovi

| File | Contenuto | Motivazione |
|---|---|---|
| `app/src/main/java/it/agoldoni/bike/SavedRide.kt` | `data class SavedRide` (id, istante di fine, durata, distanza, velocità media, kcal, massa, lista di località, stato del geocoding) | Il modello del giro salvato non esiste: `RideState` (`RideTracker.kt:14-32`) è lo stato *vivo* del giro, senza data né identità, e viene azzerato a ogni START. |
| `app/src/main/java/it/agoldoni/bike/RideStore.kt` | Lettura, scrittura e cancellazione dello storico su file privato dell'app | Nessuna persistenza di collezioni esiste oggi: `RiderProfileStore` (`RiderProfile.kt:16-40`) e `LastKnownPosition` (`LastKnownPosition.kt:11-34`) usano `SharedPreferences`, adatte a poche chiavi scalari e non a una lista di record con liste annidate. |
| `app/src/main/java/it/agoldoni/bike/RouteDigest.kt` | Campionamento del tracciato e deduplica delle località consecutive. Kotlin puro, nessuna dipendenza Android | È la logica che decide *quali* punti geocodificare e *come* si riduce il risultato: è il cuore testabile della feature e deve stare fuori dai file che toccano `android.*`, come già fa `CalorieModel`. |
| `app/src/main/java/it/agoldoni/bike/PlaceResolver.kt` | Reverse geocoding: `Geocoder` di sistema, ripiego Nominatim, normalizzazione del nome, throttling, cache | Unico punto che parla con l'esterno. Isolato dietro un'interfaccia per poter essere sostituito da un fake nei test, come `RideMap.kt` isola osmdroid. |
| `app/src/main/java/it/agoldoni/bike/SaveRideDialog.kt` | Dialog «Salvare questo giro?» con le statistiche | Composable in file proprio, sullo stampo di `WeightsDialog.kt`. |
| `app/src/main/java/it/agoldoni/bike/HistoryScreen.kt` | Lista dei giri salvati, dettaglio, stato vuoto, cancellazione con conferma | Seconda schermata dell'app: oggi ne esiste una sola. |
| `app/src/test/java/it/agoldoni/bike/RouteDigestTest.kt` | Test su campionamento e deduplica | — |
| `app/src/test/java/it/agoldoni/bike/SavedRideTest.kt` | Test su serializzazione/deserializzazione e compatibilità del formato | — |

### Modificati

| File | Modifica | Motivazione |
|---|---|---|
| `RideTracker.kt` | Nuovo flow `pendingRide: StateFlow<FinishedRide?>`, popolato da `onStop()` e azzerato da `onStart()` e da un nuovo `onRideHandled()` | `onStop()` (`RideTracker.kt:61-63`) oggi si limita a `isTracking = false, speedKmh = 0f`: non c'è alcun segnale «il giro è finito, chiedi cosa farne», e soprattutto non c'è una copia congelata. `onStart()` (`RideTracker.kt:55-59`) azzera stato, posizione e tracciato: senza congelamento, il giro da salvare sparirebbe sotto le mani della UI. Coerente con la scelta dei flow separati per ritmi diversi documentata in `RideTracker.kt:39-43`. |
| `TrackingService.kt` | `stop()` passa a `RideTracker.onStop()` l'istante di fine | `stop()` (`TrackingService.kt:148-155`) è l'unico chiamante. Il service ragiona in `SystemClock.elapsedRealtime()` (`TrackingService.kt:77`), che è un tempo dall'avvio del dispositivo: per datare un giro nello storico serve invece l'orologio a muro. |
| `MainActivity.kt` | Osserva `pendingRide` e mostra `SaveRideDialog`; ospita la navigazione fra schermata giro e storico; pulsante d'accesso allo storico | `RideScreen` (`MainActivity.kt:117-190`) tiene già lo stato solo-UI con `rememberSaveable` (pannello, inseguimento, dialog pesi): il dialog di salvataggio e la schermata attiva seguono lo stesso schema. L'overlay va nel `Box` di `MainActivity.kt:135-188`, dove sta già il pulsante «Ricentra» in `TopEnd` (`MainActivity.kt:147-161`). |
| `strings.xml` | Stringhe di dialog, storico, stati vuoto/errore | Tutte le stringhe UI passano da `stringResource` (`RidePanel.kt:110-112`, `WeightsDialog.kt:45`). |
| `gradle/libs.versions.toml` | Plugin e runtime di serializzazione | Le versioni sono centralizzate qui per convenzione di progetto; nessuna inline in `build.gradle.kts`. |
| `app/build.gradle.kts` | Plugin di serializzazione e nuova `implementation` | — |
| `AndroidManifest.xml` | Dichiarazione esplicita di `android:allowBackup` | Oggi l'attributo **non è dichiarato** (`AndroidManifest.xml:13-18`), quindi vale il default `true`: lo storico dei giri — cioè una cronologia degli spostamenti — finirebbe nel backup automatico di Android fuori dal dispositivo. Va deciso, non lasciato al default. |

### Non toccati (verificato)

`CalorieModel.kt`, `RideMap.kt`, `RidePanel.kt`, `RiderProfile.kt`, `LastKnownPosition.kt`, `WeightsDialog.kt`.
In particolare **`RidePanel.kt` resta invariato**: l'accesso allo storico non va messo dentro il pannello perché l'intera superficie è già cliccabile per espanderlo (`RidePanel.kt:79-83`) e un pulsante interno costringerebbe a gestire il consumo dell'evento. Va nel `Box` della schermata, come «Ricentra».

---

## B. Contratti e interfacce da modificare

### B.1 `RideTracker` — nuovo contratto di fine giro

```kotlin
data class FinishedRide(
    val endedAtMillis: Long,
    val state: RideState,
    val track: List<GeoPoint>,
)

fun onStop(endedAtMillis: Long = System.currentTimeMillis())
val pendingRide: StateFlow<FinishedRide?>
fun onRideHandled()   // risposta data: salvato o scartato
```

- **Non breaking** per i chiamanti esistenti: `endedAtMillis` ha un default, quindi
  `TrackingService.stop()` (`TrackingService.kt:152`) e i test continuano a compilare.
- Il test esistente `onStop conserva le statistiche del giro` resta valido: `onStop()`
  continua a non azzerare nulla.
- Il test esistente `onStart azzera il giro precedente`
  (`RideTrackerTest.kt:25-42`) va **esteso** per verificare che azzeri anche
  `pendingRide`, altrimenti il `@Before reset()` (`RideTrackerTest.kt:19-21`) lascerebbe
  sporcizia fra un test e l'altro.

### B.2 Formato di persistenza — contratto verso le versioni future

Il file dello storico è l'unico contratto della feature che non si può cambiare
liberamente: dopo il primo rilascio ci sono dati utente scritti con quel formato.

- Percorso: `context.filesDir/rides.json` (storage privato dell'app).
- Il record deve contenere un campo **`version`**: aggiungere campi in futuro (per
  esempio la polilinea, oggi fuori scope) senza rompere la lettura dei giri già salvati.
- Scrittura **atomica**: file temporaneo + `renameTo`. Riscrivendo l'intero file a ogni
  salvataggio, una morte del processo a metà scrittura lascerebbe altrimenti uno storico
  troncato, cioè la perdita di *tutti* i giri invece che dell'ultimo.
- Dimensione: ~300 byte per giro, un giro al giorno per dieci anni ≈ 1 MB. Leggere tutto
  in un colpo solo è accettabile e non richiede un database.

**Scelta della libreria di serializzazione — nodo tecnico.** `org.json` è dentro
`android.jar` e quindi *non è usabile negli unit test JVM*: nei test le classi
dell'SDK sono stub che lanciano `RuntimeException("Stub!")`. Il progetto ha solo
`app/src/test` (unit test JVM, JUnit 4) — **nessun `androidTest`, nessun Robolectric,
verificato con `find app/src -type d`** — quindi usare `org.json` renderebbe la
serializzazione non testabile, contro la convenzione per cui la logica testabile vive
fuori dai file Android. Si raccomanda `kotlinx.serialization` (plugin allineato a Kotlin
2.1.20, già la versione del progetto), che è JVM puro e serve **sia** per il file dello
storico **sia** per il parsing della risposta Nominatim.

### B.3 `PlaceResolver` — interfaccia verso l'esterno

```kotlin
interface PlaceResolver {
    suspend fun placeAt(point: GeoPoint): String?   // null = non risolto
}
```

Due implementazioni concrete più una che le concatena. Il ripiego scatta su `null` o su
timeout, mai su eccezione propagata.

**Normalizzazione dei nomi** — i due canali non restituiscono gli stessi campi, e senza
una regola comune lo stesso giro produce sommari diversi a seconda di chi ha risposto:

| Sorgente | Campi, in ordine di preferenza |
|---|---|
| `android.location.Address` | `locality` → `subAdminArea` → `adminArea` |
| Nominatim (`address`, `zoom=10`) | `city` → `town` → `village` → `municipality` → `county` |

**Nominatim**: endpoint `https://nominatim.openstreetmap.org/reverse` con
`format=jsonv2&addressdetails=1&zoom=10`. Obblighi da rispettare: **User-Agent
identificativo** (l'`applicationId` `it.agoldoni.bike` più un contatto) e **massimo 1
richiesta al secondo**.

**Client HTTP**: `java.net.HttpURLConnection` basta per una singola GET e non aggiunge
dipendenze; OkHttp porterebbe circa 800 KB di APK per lo stesso risultato. Il progetto
oggi **non fa alcuna chiamata HTTP propria**: osmdroid scarica le tile per conto suo e le
uniche voci di rete nel manifest sono `INTERNET` e `ACCESS_NETWORK_STATE`
(`AndroidManifest.xml:5-6`), già presenti — **nessun nuovo permesso da chiedere**.

### B.4 Navigazione — nessuna libreria, stato locale

Il progetto non ha `navigation-compose` e non ha ViewModel (per scelta, vedi
`CLAUDE.md`). Con due sole schermate basta uno stato in `RideScreen` sullo stampo di
`panelExpanded` (`MainActivity.kt:125`), più un `BackHandler` per il tasto indietro.
Aggiungere la libreria di navigazione per due schermate non si giustifica.

---

## C. Pattern da rispettare

- **Lingua**: tutto in italiano — commenti, KDoc, stringhe UI, nomi dei test in backtick
  (`fun \`onStart azzera il giro precedente\`()`), messaggi di commit.
- **Commenti sul perché**: ogni costante nuova (soglia minima del giro, passo di
  campionamento, timeout del geocoder, intervallo fra le richieste) va accompagnata dalla
  ragione per cui esiste, come `MAX_ACCURACY_M` e `MIN_STEP_M` in
  `TrackingService.kt:204-210`.
- **Store come `object` con `load`/`save`**: firma `(context: Context, …)`, senza stato
  interno — stampo di `RiderProfileStore` e `LastKnownPosition`. Il nome file
  `SharedPreferences` condiviso è `"bike"` (`RiderProfile.kt:18`,
  `LastKnownPosition.kt:13`): se serve una chiave scalare nuova va lì, non in un file
  nuovo.
- **Flow separati per ritmi diversi** (`RideTracker.kt:39-43`): `pendingRide` cambia una
  volta per giro e va tenuto fuori da `state`, che emette ogni secondo.
- **Un solo punto di mutazione**: i metodi `on*` di `RideTracker`.
- **Composable in file propri**, parametri `on*: () -> Unit` verso l'alto, nessuno stato
  di dominio dentro la UI (`WeightsDialog.kt:30-34`).
- **Formattazione numerica** con `Locale.ITALY` (`RidePanel.kt:99`, `RidePanel.kt:197-206`);
  `formatElapsed` è oggi `private` in `RidePanel.kt:197` e il dialog ne ha bisogno: va
  reso condiviso invece di duplicato.
- **Colori inline** in stile schermata scura (`Color(0xFA000000)`, `RidePanel.kt:84`),
  tema `darkColorScheme()` (`MainActivity.kt:58`).
- **Versioni solo in `gradle/libs.versions.toml`**, mai inline nel `build.gradle.kts`.

---

## D. Test da creare o aggiornare

Framework: JUnit 4, unit test JVM. `sdk env && ./gradlew testDebugUnitTest`.

| Area | File | Tipo | Contenuto |
|---|---|---|---|
| Fine giro | `RideTrackerTest.kt` *(esistente, da estendere)* | unit | `onStop` pubblica lo snapshot con statistiche e tracciato; `onStart` azzera `pendingRide`; `onRideHandled` lo azzera; un secondo `onStop` non duplica. |
| Campionamento | `RouteDigestTest.kt` *(nuovo)* | unit | Tracciato vuoto → nessun punto; primo e ultimo punto sempre inclusi; il passo rispetta la distanza minima; un giro molto lungo non supera il tetto di richieste, allargando il passo invece di troncare la coda. |
| Deduplica | `RouteDigestTest.kt` *(nuovo)* | unit | Località consecutive uguali collassano in una; una località riattraversata dopo esserne usciti ricompare (`A → B → A`); i `null` non rompono la sequenza. |
| Normalizzazione | `RouteDigestTest.kt` o `PlaceResolverTest.kt` *(nuovo)* | unit | Ordine di preferenza dei campi; tutti i campi nulli → `null`, non stringa vuota. Testabile solo se la normalizzazione lavora su un tipo di dominio e non su `android.location.Address`. |
| Persistenza | `SavedRideTest.kt` *(nuovo)* | unit | Andata e ritorno di serializzazione; un record scritto da una versione futura con campi in più si legge lo stesso; file assente o corrotto → lista vuota, non crash. |
| Geocoding a due canali | `PlaceResolverTest.kt` *(nuovo)* | unit con fake | Il ripiego scatta su `null` e su timeout; non scatta se il primo canale ha risposto; la cache evita la seconda richiesta per lo stesso punto; il throttling rispetta l'intervallo. Richiede che `PlaceResolver` sia un'interfaccia e che le implementazioni Android stiano dietro di essa. |

**Non coperto da unit test** (limite strutturale del progetto, non della feature): tutto
ciò che tocca `Context`, `Geocoder`, file I/O reale e Compose. Verifica manuale:

- `./demo-ride.sh` — utile proprio qui: l'emulatore AOSP **non ha Google Play services**
  (`CLAUDE.md`, sezione «Sviluppo su emulatore»), quindi il `Geocoder` di sistema resta
  muto ed è il banco di prova naturale del ramo Nominatim. Serve però che l'emulatore
  abbia rete verso internet.
- Giro reale su telefono con GMS per il ramo primario.
- `./build.sh release` — il lint della release è severo e ha già bloccato una build per
  una dipendenza transitiva disallineata (vedi il commento in
  `gradle/libs.versions.toml` su `androidx-fragment`): con una dipendenza nuova va
  verificata la release, non solo la debug.

---

## E. Rischi tecnici aggiornati

| Rischio | Evidenza dalla codebase | Mitigazione |
|---|---|---|
| **Il giro sparisce mentre il dialog è aperto** | `RideTracker.onStart()` (`RideTracker.kt:55-59`) assegna `RideState()` e svuota `_track`. Il pulsante torna «START» appena `isTracking` è falso (`RidePanel.kt:162`). | Congelare `FinishedRide` in `onStop()`. Il dialog modale non-dismissibile copre il pulsante, ma la copia è la difesa vera. |
| **Snapshot solo in memoria** | `stop()` chiama `RideTracker.onStop()` e poi `stopSelf()` (`TrackingService.kt:152-154`): finito il foreground service il processo diventa uccidibile. Se muore prima della risposta, il giro è perso senza che l'utente abbia risposto. | Accettato e dichiarato in Fase 1 (fuori scope il recupero). In alternativa: scrivere subito il giro come «non confermato» e chiedere alla riapertura — costo non banale. |
| **Volume di richieste di geocoding** | Un vertice ogni `TRACK_STEP_M = 10f` (`TrackingService.kt:210`): un giro di 30 km produce ~3000 vertici. Geocodificarli tutti significherebbe 3000 richieste, cioè quasi un'ora a 1 req/s e il blocco certo dell'IP su Nominatim. | Campionamento per distanza cumulata (un punto ogni ~2 km → ~16 richieste per 30 km), tetto massimo di richieste per giro, cache per punti vicini. |
| **`Geocoder.getFromLocation` deprecato** | Deprecato da API 33; la variante con listener esiste **solo** da API 33, mentre il progetto ha `minSdk 26`. Servono entrambi i rami. | Ramo `Build.VERSION.SDK_INT >= 33` con la variante asincrona, ramo legacy bloccante su `Dispatchers.IO` sotto — lo stesso schema già usato per `POST_NOTIFICATIONS` in `MainActivity.kt:96`. Verificare il lint della release sul ramo deprecato. |
| **Geocoder muto invece che assente** | `Geocoder.isPresent()` può essere vero e la chiamata restituire comunque lista vuota o restare appesa. | Il ripiego deve scattare su timeout, non solo su `isPresent()`. Timeout esplicito con `withTimeoutOrNull`. |
| **Chi esegue il geocoding dopo lo STOP** | Il service si autodistrugge (`TrackingService.kt:154`) e l'Activity può essere distrutta in qualunque momento: nessuno dei due è un contenitore affidabile per un lavoro di decine di secondi. Il progetto **non ha WorkManager**. | Salvare subito il giro con le sole statistiche e risolvere le località in un secondo momento, aggiornando il record; se il lavoro non arriva in fondo, lo storico mostra «località non disponibili» e il tentativo si può ripetere all'apertura dello storico. WorkManager sarebbe la soluzione robusta ma è una dipendenza nuova. |
| **Backup cloud dello storico** | `android:allowBackup` non dichiarato in `AndroidManifest.xml:13-18` → default `true`. | Dichiararlo esplicitamente. Decisione aperta (vedi F). |
| **osmdroid al rientro dallo storico** | `RideMap` gestisce a mano `onResume`/`onPause`/`onDetach` (per KDoc del progetto): passare a un'altra schermata smonta la `MapView`. È la sorgente di bug già segnalata nei rischi di `001-view-on-map`. | Verificare a mano il rientro: mappa viva, tracciato ancora disegnato, `followPosition` coerente. |
| **Dimensione dell'APK e lint** | `isMinifyEnabled = false` (`app/build.gradle.kts`, buildType release): nulla viene rimosso, quindi ogni dipendenza pesa per intero. | Preferire `HttpURLConnection` a OkHttp; `kotlinx-serialization-json` resta l'unica aggiunta significativa. |
| **`formatElapsed` duplicato** | È `private` in `RidePanel.kt:197-206` e serve identico a dialog e storico. | Estrarlo in un punto condiviso alla prima ri-necessità, non copiarlo. |

---

## F. Prerequisiti e task bloccanti

Nessun refactoring architetturale è necessario: il flusso `TrackingService` produce →
`RideTracker` conserva → UI osserva regge la feature così com'è, e `RideTracker` è già il
punto giusto dove agganciare la fine del giro.

Restano **quattro decisioni da prendere prima di iniziare**, tutte bloccanti per M1:

1. **Libreria di serializzazione** — `kotlinx.serialization` (raccomandata: testabile in
   JVM, serve anche per Nominatim) contro JSON scritto a mano (zero dipendenze, ma
   codice noioso e fragile). `org.json` è escluso perché non testabile negli unit test JVM.
2. **`allowBackup`** — includere o no la cronologia degli spostamenti nel backup
   automatico di Android. Va scritto nel manifest in un senso o nell'altro, con il
   commento che spiega la scelta.
3. **Passo di campionamento e tetto di richieste** — quanti chilometri fra un punto
   geocodificato e l'altro. Determina la granularità del sommario (un giro corto in
   pianura padana potrebbe risultare in una sola località) e il carico su Nominatim.
4. **Soglia minima sotto la quale non si chiede** — distanza, durata, o entrambe.

Task tecnici preparatori, non bloccanti ma da fare presto:

- Verificare che l'emulatore usato da `demo-ride.sh` abbia rete verso
  `nominatim.openstreetmap.org`: senza, il ramo di ripiego non è testabile in demo e
  resta senza copertura fino al primo giro reale.
- Verificare `lintRelease` subito dopo l'aggiunta della dipendenza di serializzazione,
  prima di scriverci sopra codice.

---

*Documento generato con la skill `claude-code-feature` — Fase 2.*
