# salva-giro — Implementation Plan

**Stato:** Implementata il 2026-08-11 — vedi «Esito dell'implementazione» in fondo
**Autore:** Alberto Goldoni (documento redatto con assistenza di Claude Code)
**Data:** 2026-08-11
**Versione:** 1.2

---

## 1. Executive Summary

Oggi il giro vive solo in memoria: premendo START una seconda volta il giro precedente
sparisce senza lasciare traccia. Questa feature chiude il ciclo di vita del giro: al tap
su **STOP** l'app chiede se salvarlo, e il giro salvato entra in uno storico consultabile
dentro l'app. Del percorso non si conserva la linea disegnata sulla mappa ma i suoi
*punti salienti* — le città e le località attraversate in ordine, per esempio
«Modena → Formigine → Maranello → Modena» — perché è la forma di riassunto che resta
leggibile a distanza di mesi e occupa poche centinaia di byte per giro. I nomi si
ricavano per reverse geocoding, usando il servizio di sistema con ripiego su OpenStreetMap
quando il primo non risponde. Stima: circa **6,75 giorni/uomo** per un singolo
sviluppatore.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** `RideTracker` è un `object` singleton senza persistenza e
  `onStart()` azzera stato, posizione e tracciato
  ([RideTracker.kt:55-59](../../../app/src/main/java/it/agoldoni/bike/RideTracker.kt#L55-L59)).
  Alla partenza successiva — o alla morte del processo — il giro precedente non esiste
  più. È la lacuna che la feature `001-view-on-map` aveva dichiarato fuori scope,
  rimandandola a una feature «separata e prerequisita»: questa.

- **Metriche di successo:**
  - [ ] Nessun giro concluso con lo STOP viene perso senza che l'utente abbia risposto
        alla domanda.
  - [ ] Un giro di 30 km produce **al massimo 40 richieste** di reverse geocoding
        (contro i ~3000 vertici del tracciato).
  - [ ] Il sommario di un giro noto è riconoscibile senza guardare la data: le località
        elencate corrispondono ai comuni realmente attraversati, nell'ordine giusto.
  - [ ] Un giro fatto in una zona senza copertura dati si salva comunque, con le
        statistiche complete.
  - [ ] Il dialog a fine giro non introduce attesa percepibile: si chiude subito, il
        geocoding prosegue dopo.

- **Legame con obiettivi di prodotto:** è il prerequisito dichiarato di tutto ciò che
  riguarda la storia dei giri — export GPX, statistiche aggregate, replay su mappa — che
  oggi non è pianificabile perché non esiste alcuna persistenza del giro.

---

## 3. Scope

### Incluso

- Dialog «Salvare questo giro?» al tap su STOP, con le statistiche del giro appena
  concluso e due sole azioni: *Salva* e *Non salvare*.
- Soglia minima di **distanza** sotto la quale la domanda non compare e il giro viene
  scartato in silenzio.
- Campionamento del tracciato **ogni 1 km** e reverse geocoding dei soli punti campionati.
- Deduplica delle località consecutive uguali, con conservazione delle riapparizioni non
  consecutive (andata e ritorno resta `A → B → A`).
- Reverse geocoding a due canali: `android.location.Geocoder` di sistema come primario,
  Nominatim (OpenStreetMap) come ripiego su timeout o risposta vuota.
- Persistenza dei giri salvati in `rides.json` nello storage privato dell'app, con
  scrittura atomica.
- Schermata storico: lista dal più recente, dettaglio, stato vuoto, cancellazione con
  conferma.
- Degrado esplicito senza rete: il giro si salva con le sole statistiche e lo storico
  dichiara «località non disponibili».

### Escluso (out of scope)

- **Replay del tracciato su mappa** — conseguenza diretta della scelta di salvare solo i
  punti salienti: la polilinea non viene conservata. Richiederebbe un altro profilo di
  occupazione disco e un'altra schermata.
- **Esportazione GPX e condivisione del sommario** — dipendono da questa feature ma sono
  una superficie a sé (formato di scambio, share sheet, permessi).
- **Recupero di un giro interrotto dalla morte del processo** — richiede persistenza
  incrementale *durante* il giro, non a fine giro. Feature separata.
- **Titolo o note scritti a mano** — il sommario è generato, non editabile.
- **Statistiche aggregate** (totali settimanali, grafici, record personali).
- **Sincronizzazione cloud, account, backup fuori dal dispositivo** — vedi D-02.
- **Modifica retroattiva dei pesi** su un giro già salvato.

### Decisioni prese

Le quattro decisioni bloccanti emerse in Fase 2 sono state chiuse prima della stesura di
questo documento.

| # | Decisione | Esito | Conseguenze |
|---|---|---|---|
| D-01 | Libreria di serializzazione | **`kotlinx.serialization`** | Plugin `org.jetbrains.kotlin.plugin.serialization` allineato a Kotlin 2.1.20 e runtime `kotlinx-serialization-json`, entrambi da dichiarare in `gradle/libs.versions.toml`. È JVM puro, quindi il formato del file resta coperto da unit test, e serve anche per il parsing della risposta Nominatim. `org.json` era escluso perché vive in `android.jar` e negli unit test JVM lancia `RuntimeException("Stub!")`. |
| D-02 | Backup automatico | **`android:allowBackup="false"`** | Lo storico è una cronologia degli spostamenti e non deve uscire dal dispositivo. Va dichiarato esplicitamente nel manifest, dove oggi l'attributo è assente e vale quindi il default `true`. Conseguenza da accettare: cambiando telefono i giri **non** si trasferiscono. |
| D-03 | Passo di campionamento | **1 km**, con tetto di **40 richieste per giro** | Un giro di 30 km → 31 punti geocodificati invece di ~3000 vertici. Oltre i 40 km il passo si allarga a `distanza / 40` invece di troncare la coda: troncare perderebbe la parte finale del giro, cioè quasi sempre il rientro. Caso peggiore su Nominatim: 40 s a 1 richiesta/secondo. |
| D-04 | Criterio della soglia minima | **Distanza** (non durata) | Un giro fermo con l'app aperta per un'ora resta sotto soglia e non chiede nulla; un giro corto ma reale la supera. Valore proposto: **300 m** — con `MIN_STEP_M = 2f` e il filtro di accuratezza, da fermi la distanza contata resta prossima a zero, quindi 300 m distingue con ampio margine la partenza per errore. Il valore è una costante isolata e resta facile da rivedere dopo il primo uso reale. |
| D-05 | Contatto nello User-Agent di Nominatim | **`alberto.goldoni@gmail.com`** | User-Agent completo: `Bike/1.0 (it.agoldoni.bike; alberto.goldoni@gmail.com)`. Da mettere in conto: l'indirizzo finisce in chiaro dentro l'APK e nei log del server a ogni richiesta. È ciò che la usage policy richiede, e serve a farsi avvertire invece che bloccare in caso di uso anomalo. |
| D-06 | Ritentativo del geocoding | **Automatico** all'apertura dello storico | I giri con `placesResolved = false` vengono ritentati da soli. Il traffico generato è trascurabile — al massimo 40 richieste da poche centinaia di byte per giro irrisolto — e evita un'affordance in più nella UI. Il ritentativo va però fatto **una volta per apertura**, non a ogni ricomposizione, e non deve partire senza rete. |
| D-07 | Granularità dei nomi | **`zoom=10` (comune)**, accettato | Un giro tutto dentro una città produce una sola voce nel sommario. Accettato per la prima versione: scendere a quartiere o frazione moltiplicherebbe le voci sui giri lunghi, che sono il caso in cui il sommario serve davvero. Da rivalutare dopo qualche giro urbano reale. |
| D-08 | Accesso allo storico | **Pulsante in `TopStart`** | Simmetrico a «Ricentra», che sta in `TopEnd`. Fuori dal pannello, che è già interamente cliccabile per l'espansione. |
| D-09 | Conferma sullo scarto | **Nessuna seconda conferma** | «Non salvare» agisce subito. La difesa contro il tocco involontario resta il dialog non-dismissibile, che impone una scelta esplicita fra due pulsanti distanziati. |

---

## 4. User Stories e criteri di accettazione

### US-001 · Non perdere il giro appena finito
**Priorità:** Must Have

Come ciclista voglio che a fine giro l'app mi chieda se salvarlo, per non scoprire alla
partenza successiva che il giro di ieri non esiste più.

**Criteri di accettazione:**
- [ ] Al tap su STOP il tracciamento si ferma e compare un dialog che chiede se salvare.
- [ ] Il dialog mostra durata, distanza, velocità media e kcal del giro appena concluso.
- [ ] Finché il dialog è aperto i dati del giro restano intatti e leggibili sotto.
- [ ] Toccando *Salva* il giro entra nello storico e il dialog si chiude subito, senza
      attendere il geocoding.
- [ ] Il pulsante START non è raggiungibile finché la domanda non ha avuto risposta: la
      scelta non si può aggirare.
- [ ] Sotto i 300 m di distanza il dialog non compare affatto.

### US-002 · Buttare via i giri di prova
**Priorità:** Must Have

Come ciclista voglio poter rispondere «non salvare», per non riempire lo storico con le
partenze fatte per errore o con le prove sotto casa.

**Criteri di accettazione:**
- [ ] Toccando *Non salvare* il dialog si chiude e nulla viene scritto su disco.
- [ ] Il giro scartato non compare nello storico.
- [ ] Il dialog non è chiudibile toccando fuori né col tasto indietro: serve una risposta
      esplicita.
- [ ] *Non salvare* agisce subito, senza una seconda conferma (D-09).

### US-003 · Riconoscere un giro dalle località
**Priorità:** Must Have

Come ciclista voglio vedere elencate le città e le località che ho attraversato, per
capire di quale giro si tratta senza dover ricordare la data.

**Criteri di accettazione:**
- [ ] Per un giro con tracciato non vuoto e rete disponibile, il giro salvato contiene
      almeno la località di partenza e quella di arrivo.
- [ ] Le località sono in ordine di percorrenza.
- [ ] La stessa località non compare due volte di fila: un giro dentro un solo comune
      produce una sola voce, non una per punto campionato.
- [ ] Una località riattraversata dopo esserne usciti ricompare: andata e ritorno mostra
      `A → B → A`.
- [ ] Le richieste di geocoding per giro non superano 40, qualunque sia la lunghezza.
- [ ] Le località compaiono nello storico appena risolte, senza che serva riaprire l'app.

### US-004 · Rivedere i giri passati
**Priorità:** Must Have

Come ciclista voglio una schermata con l'elenco dei giri salvati, per confrontare
distanza, durata e calorie con le uscite precedenti.

**Criteri di accettazione:**
- [ ] Dallo schermo principale si raggiunge lo storico senza interferire col pulsante
      START/STOP né con l'espansione del pannello.
- [ ] I giri sono elencati dal più recente al più vecchio.
- [ ] Ogni riga mostra data e ora, distanza, durata e il sommario delle località.
- [ ] Il tap su una riga apre il dettaglio con tutte le statistiche salvate.
- [ ] Con storico vuoto compare un messaggio esplicativo, non una lista vuota muta.
- [ ] I giri salvati sopravvivono alla chiusura dell'app e al riavvio del dispositivo.
- [ ] Il tasto indietro dallo storico riporta alla schermata del giro, con la mappa
      ancora viva e il tracciato disegnato.

### US-005 · Fare pulizia nello storico
**Priorità:** Should Have

Come ciclista voglio cancellare un giro dallo storico, per rimuovere le uscite che non mi
interessa conservare.

**Criteri di accettazione:**
- [ ] Un giro può essere cancellato dallo storico.
- [ ] La cancellazione chiede conferma.
- [ ] Dopo la conferma il giro sparisce dalla lista e dal file su disco.

### US-006 · Salvare anche senza copertura dati
**Priorità:** Must Have

Come ciclista voglio che il giro si salvi comunque quando sono in una valle senza rete,
per non perdere le statistiche solo perché non si sono potuti risolvere i nomi dei paesi.

**Criteri di accettazione:**
- [ ] Senza rete il giro si salva con le sole statistiche.
- [ ] Un giro privo di località lo dichiara esplicitamente nello storico («località non
      disponibili»), invece di mostrare una riga vuota.
- [ ] Il fallimento del geocoding non fa perdere il giro né manda l'app in crash.
- [ ] Tornata la rete, all'apertura dello storico i giri irrisolti vengono ritentati da
      soli, una volta per apertura (D-06).
- [ ] Se il `Geocoder` di sistema non risponde entro il timeout si passa a Nominatim
      senza attesa percepibile dall'utente.
- [ ] Le richieste a Nominatim rispettano il limite di 1 al secondo e inviano uno
      User-Agent identificativo dell'app.

---

## 5. Architettura tecnica

### Componenti coinvolti

Il flusso a senso unico del progetto — service produce, `RideTracker` conserva, UI osserva
— non cambia: la feature aggiunge un ramo che parte dalla fine del giro.

```
  [utente preme STOP]
          │
          ▼
  TrackingService.stop() ──► RideTracker.onStop(endedAtMillis)
                                      │
                                      ▼
                          pendingRide: StateFlow<FinishedRide?>
                                      │ (osservato dalla UI)
                                      ▼
                           MainActivity ──► SaveRideDialog
                              │                      │
                       «Non salvare»              «Salva»
                              │                      │
                    onRideHandled()          RideStore.add(SavedRide senza località)
                                                     │
                                                     ▼
                                    RouteDigest.sample(track, 1 km, max 40)
                                                     │
                                                     ▼
                              PlaceResolver:  Geocoder di sistema
                                                  └─(timeout/vuoto)─► Nominatim
                                                     │
                                                     ▼
                                      RouteDigest.dedupConsecutive(nomi)
                                                     │
                                                     ▼
                                    RideStore.updatePlaces(id, località)
                                                     │
                                                     ▼
                                              HistoryScreen
```

`RouteDigest` è Kotlin puro senza dipendenze Android, come `CalorieModel`: è lì che vive
tutta la logica testabile della feature. `PlaceResolver` è l'unico componente che parla
con l'esterno ed è l'analogo di `RideMap` per osmdroid — una frontiera dietro cui la
tecnologia resta sostituibile.

### Modifiche al data model

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `FinishedRide` | Nuovo | `endedAtMillis: Long`, `state: RideState`, `track: List<GeoPoint>`. Copia congelata del giro concluso, in memoria. Serve perché `onStart()` azzera tutto e il giro da salvare sparirebbe sotto le mani della UI. |
| `SavedRide` | Nuovo | `id: String`, `endedAtMillis: Long`, `elapsedMillis: Long`, `distanceMeters: Float`, `avgSpeedKmh: Float`, `kcal: Float`, `totalMassKg: Float`, `places: List<String>`, `placesResolved: Boolean`. `placesResolved` distingue «giro senza località perché il geocoding non è riuscito» da «giro non ancora elaborato», che nella UI vanno detti in modo diverso. |
| `rides.json` | Nuovo | File in `context.filesDir`. Struttura: `{ "version": 1, "rides": [ … ] }`. Il campo `version` esiste per poter aggiungere campi in futuro (per esempio la polilinea, oggi fuori scope) senza rompere la lettura dei giri già salvati. Scrittura atomica: file temporaneo più `renameTo`, perché riscrivendo l'intero file una morte del processo a metà lascerebbe uno storico troncato — cioè la perdita di *tutti* i giri invece che dell'ultimo. |
| `RideState` | Invariato | Resta lo stato *vivo* del giro. Non acquisisce data né identità. |
| `SharedPreferences "bike"` | Invariato | Continua a ospitare solo chiavi scalari (pesi, ultima posizione). Lo storico non ci sta: è una lista di record con liste annidate. |

### Nuove API o endpoint

L'app non espone API. Consuma un solo endpoint esterno:

| Metodo | Endpoint | Descrizione | Auth richiesta |
|---|---|---|---|
| GET | `https://nominatim.openstreetmap.org/reverse?format=jsonv2&addressdetails=1&zoom=10&lat=…&lon=…` | Reverse geocoding di ripiego quando il `Geocoder` di sistema non risponde | No — ma obbligatori uno **User-Agent identificativo** e il rispetto del limite di **1 richiesta/secondo** |

Client: `java.net.HttpURLConnection`. Basta per una singola GET e non aggiunge
dipendenze; OkHttp costerebbe circa 800 KB di APK per lo stesso risultato, e con
`isMinifyEnabled = false` nel buildType release nulla verrebbe rimosso.

I permessi `INTERNET` e `ACCESS_NETWORK_STATE` sono **già presenti** nel manifest per le
tile OSM: nessun permesso nuovo da chiedere all'utente.

**Normalizzazione dei nomi** — i due canali non restituiscono gli stessi campi, e senza
una regola comune lo stesso giro produrrebbe sommari diversi a seconda di chi ha risposto:

| Sorgente | Campi, in ordine di preferenza |
|---|---|
| `android.location.Address` | `locality` → `subAdminArea` → `adminArea` |
| Nominatim (oggetto `address`, `zoom=10`) | `city` → `town` → `village` → `municipality` → `county` |

### Breaking changes

Nessun breaking change verso l'esterno: l'app non ha API pubbliche né dati preesistenti
da migrare — `rides.json` nasce con questa feature.

| Componente | Tipo di breaking change | Piano di migrazione |
|---|---|---|
| `RideTracker.onStop()` | Acquisisce il parametro `endedAtMillis: Long`, **con valore di default** | Nessuna migrazione: l'unico chiamante (`TrackingService.stop()`) e i test esistenti continuano a compilare. |
| `RideTrackerTest` | Il `@Before reset()` chiama `onStart()`, che ora azzera anche `pendingRide` | Il test `onStart azzera il giro precedente` va **esteso** per verificarlo, altrimenti resta sporcizia fra un test e l'altro. |
| `rides.json` v1 | Contratto verso le versioni future dell'app | Il campo `version` e una lettura tollerante ai campi sconosciuti rendono retrocompatibili le aggiunte successive. |

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da |
|---|---|---|---|---|
| T-01 | Dipendenze: plugin e runtime `kotlinx.serialization` in `libs.versions.toml` e `build.gradle.kts`; verifica immediata di `lintRelease` prima di scriverci sopra codice | Infra | 0,25 | — |
| T-02 | `SavedRide.kt`: modello e serializzazione, con campo `version` e lettura tollerante | Core | 0,5 | T-01 |
| T-03 | `RideStore.kt`: lettura, aggiunta, aggiornamento delle località, cancellazione; scrittura atomica; file assente o corrotto → lista vuota | Core | 0,75 | T-02 |
| T-04 | `AndroidManifest.xml`: `android:allowBackup="false"` con commento sulla ragione | Infra | 0,1 | — |
| T-05 | `RideTracker`: `FinishedRide`, flow `pendingRide`, `onStop(endedAtMillis)`, `onRideHandled()`; `TrackingService.stop()` passa l'orologio a muro | Core | 0,5 | — |
| T-06 | `SaveRideDialog.kt`: dialog non-dismissibile con le statistiche; `formatElapsed` estratto da `RidePanel.kt` in un punto condiviso invece di duplicato | FE | 0,5 | T-05 |
| T-07 | `MainActivity`: osservazione di `pendingRide`, soglia minima di 300 m, salvataggio o scarto | FE | 0,4 | T-03, T-06 |
| T-08 | `HistoryScreen.kt`: lista, dettaglio, stato vuoto; navigazione a stato locale con `BackHandler`; pulsante d'accesso in `TopStart` nel `Box` della schermata, simmetrico a «Ricentra» (D-08) | FE | 1,25 | T-03 |
| T-09 | Cancellazione con conferma; resa dello stato «località non disponibili» | FE | 0,5 | T-08 |
| T-10 | `RouteDigest.kt`: campionamento per distanza cumulata (1 km, tetto 40, passo allargato oltre i 40 km) e deduplica delle località consecutive | Core | 0,5 | — |
| T-11 | `PlaceResolver`: interfaccia, implementazione `Geocoder` con i due rami API 26/33+ e timeout esplicito, normalizzazione verso il tipo di dominio | Core | 0,75 | T-10 |
| T-12 | Ripiego Nominatim: `HttpURLConnection`, User-Agent `Bike/1.0 (it.agoldoni.bike; alberto.goldoni@gmail.com)` (D-05), throttling a 1 req/s, cache per cella, concatenazione con il canale primario | Core | 0,75 | T-11 |
| T-13 | Orchestrazione post-salvataggio: risoluzione delle località fuori dall'Activity e aggiornamento del record; ritentativo automatico all'apertura dello storico, una volta per apertura e solo con rete (D-06) | Core | 0,5 | T-03, T-12 |
| T-14 | Test: estensione di `RideTrackerTest`, nuovi `RouteDigestTest`, `SavedRideTest`, `PlaceResolverTest` con fake | Test | 0,75 | T-05, T-10, T-12 |
| T-15 | Verifica: `./build.sh release` con lint, `./demo-ride.sh`, giro reale su telefono con GMS | Test | 0,5 | T-14 |
| T-16 | Documentazione: aggiornamento di `CLAUDE.md` (nuovo file di persistenza, usage policy Nominatim, nuovi comandi di test) | Doc | 0,25 | T-15 |

**Stima totale:** 8,75 giorni/uomo
**Breakdown:** Core 4,25 gg · FE 2,65 gg · Infra 0,35 gg · Test 1,25 gg · Doc 0,25 gg

> La stima di Fase 1 era 6,75 gg. La differenza (+2 gg) viene dalla scomposizione in
> task: emergono l'orchestrazione post-salvataggio (T-13), i due rami API del `Geocoder`
> (T-11) e la verifica di release (T-15), che in Fase 1 erano annegati nelle voci
> aggregate. La stima da approvare è questa.

**Ordine consigliato:** T-01 → T-02 → T-03 → T-05 → T-06 → T-07 (a questo punto la
feature salva e chiede, senza località: già utile) → T-08 → T-09 (storico completo) →
T-10 → T-11 → T-12 → T-13 (località) → T-14 → T-15 → T-16.

---

## 7. Piano di test

**Strategia generale:** la logica pura sta in oggetti senza dipendenze Android
(`RouteDigest`, serializzazione di `SavedRide`) e va coperta da unit test JVM, come già
avviene per `CalorieModel` e `RideTracker`. Tutto ciò che tocca `Context`, `Geocoder`,
rete e Compose non è coperto da test automatici — è un limite strutturale del progetto,
che ha solo `app/src/test` e nessun `androidTest` — e si verifica a mano.

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | `onStop` pubblica lo snapshot con statistiche e tracciato; `onStart` e `onRideHandled` lo azzerano; un secondo `onStop` non duplica | Alta |
| TC-02 | Unit | Campionamento: tracciato vuoto → nessun punto; primo e ultimo sempre inclusi; passo di 1 km rispettato | Alta |
| TC-03 | Unit | Tetto di 40 richieste: un giro di 100 km allarga il passo invece di troncare la coda | Alta |
| TC-04 | Unit | Deduplica: località consecutive uguali collassano; `A → B → A` sopravvive; i `null` non spezzano la sequenza | Alta |
| TC-05 | Unit | Serializzazione: andata e ritorno fedele; un record con campi sconosciuti si legge lo stesso; file assente o corrotto → lista vuota, non crash | Alta |
| TC-06 | Unit (fake) | Il ripiego scatta su risposta vuota e su timeout, non scatta se il primario ha risposto; la cache evita la seconda richiesta per la stessa cella; il throttling rispetta l'intervallo | Alta |
| TC-07 | Unit | Normalizzazione: ordine di preferenza dei campi; tutti nulli → `null`, non stringa vuota | Media |
| TC-08 | Manuale | `./demo-ride.sh` — l'emulatore AOSP non ha Google Play services, quindi il `Geocoder` resta muto: è il banco di prova naturale del ramo Nominatim (serve rete verso internet dall'emulatore) | Alta |
| TC-09 | Manuale | Giro reale su telefono con GMS: ramo primario, qualità dei nomi, numero di richieste effettuate | Alta |
| TC-10 | Manuale | Giro con modalità aereo attiva a fine giro: il giro si salva, lo storico dichiara «località non disponibili» | Alta |
| TC-11 | Manuale | Rientro dallo storico alla schermata giro: mappa viva, tracciato ancora disegnato, `followPosition` coerente — è il bug più probabile, `RideMap` gestisce il ciclo di vita della `MapView` a mano | Alta |
| TC-12 | Manuale | Giro sotto i 300 m: nessun dialog, nessuna scrittura | Media |
| TC-13 | Manuale | Persistenza: chiusura dell'app e riavvio del dispositivo, i giri sono ancora lì | Alta |

### Definition of Done

- [ ] `sdk env && ./gradlew testDebugUnitTest` verde, inclusi i nuovi test.
- [ ] `./build.sh release` completa: il lint della release è severo e ha già bloccato una
      build per una dipendenza transitiva disallineata.
- [ ] `./demo-ride.sh` esegue un giro completo e il dialog compare a fine giro.
- [ ] Un giro reale su telefono produce un sommario di località corretto.
- [ ] Nessun `Log`/crash nel `logcat` durante il ciclo salvataggio → geocoding → storico.
- [ ] `CLAUDE.md` aggiornato: nuovo file di persistenza, usage policy Nominatim.
- [ ] Codice in italiano, commenti che spiegano il perché delle costanti nuove (soglia
      di 300 m, passo di 1 km, tetto di 40, timeout del geocoder, intervallo fra le
      richieste).

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Il giro sparisce mentre il dialog è aperto: `onStart()` assegna `RideState()` e svuota `_track` | Media | Alto | Congelare `FinishedRide` in `onStop()`. Il dialog non-dismissibile copre il pulsante START, ma la copia è la difesa vera. |
| Lo snapshot vive solo in memoria: dopo `stopSelf()` il processo diventa uccidibile e il giro si perde senza che l'utente abbia risposto | Media | Medio | Rischio **accettato** e dichiarato: il recupero è fuori scope. Da rivalutare se capita davvero sul campo. |
| Blocco dell'IP da parte di Nominatim per troppe richieste | Bassa | Alto | Passo di 1 km, tetto di 40 richieste per giro, throttling a 1 req/s, cache per cella, User-Agent identificativo. |
| `Geocoder.getFromLocation` deprecato da API 33, ma la variante con listener esiste solo da 33 e il progetto ha `minSdk 26` | Alta | Basso | Due rami con `Build.VERSION.SDK_INT >= 33`, stesso schema già usato per `POST_NOTIFICATIONS`. Verificare il lint della release sul ramo deprecato (T-01 e T-15). |
| Il `Geocoder` risponde `isPresent() == true` ma poi resta appeso o torna vuoto | Media | Medio | Il ripiego scatta su **timeout** con `withTimeoutOrNull`, non solo su `isPresent()`. |
| Sommario povero in città: con `zoom=10` un giro urbano lungo dà una sola località | Media | Medio | Rischio **accettato** per la prima versione (D-07), da rivalutare dopo qualche giro urbano reale. |
| Il rientro dallo storico rompe la `MapView` di osmdroid | Media | Medio | TC-11. È la sorgente di bug già segnalata nei rischi di `001-view-on-map`. |
| Il geocoding non arriva in fondo perché l'app va in background | Alta | Basso | Il giro è già salvato con le statistiche; `placesResolved = false` e ritentativo (T-13). WorkManager sarebbe la soluzione robusta, ma è una dipendenza nuova non giustificata qui. |
| Consumo dati e batteria a fine giro, quando la batteria è al minimo dopo ore di GPS | Bassa | Basso | Al massimo 40 richieste HTTP da poche centinaia di byte, una sola volta per giro, mai ripetute a ogni apertura dello storico. |
| Perdita dei giri cambiando telefono, per `allowBackup="false"` | Alta | Basso | Conseguenza accettata di D-02. Se un giorno pesa, la risposta è l'export GPX, non il backup cloud della cronologia. |

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] Deploy diretto (direct)
- [ ] Graduale con feature flag
- [ ] Canary release

Non si usano feature flag: l'app è un progetto personale con un solo utente, distribuita
via `./install-all.sh` sul telefono. Un flag aggiungerebbe complessità senza benefici.

**Piano di rollback:**
1. `git checkout` del commit precedente e `./install-all.sh --build`.
2. Nessuna migrazione da annullare. A differenza di `001-view-on-map`, questa feature
   **introduce persistenza**: dopo un rollback il file `rides.json` resta sul dispositivo
   e la versione precedente semplicemente lo ignora, senza danno. Reinstallando la
   versione nuova i giri salvati sono ancora lì.
3. Da evitare invece la disinstallazione dell'app: con `allowBackup="false"` e nessun
   export, disinstallare cancella lo storico in modo irreversibile.

---

## 10. Checklist di approvazione

| Revisione | Responsabile | Stato | Data |
|---|---|---|---|
| Revisione tecnica | Alberto (tech lead di fatto) | ⏳ In attesa | — |
| Revisione prodotto | Alberto (utente finale) | ⏳ In attesa | — |
| Stima approvata | Alberto | ⏳ In attesa | — |
| Rischi accettati | Alberto | ⏳ In attesa | — |
| Data di inizio confermata | — | ⏳ In attesa | — |

---

## Domande aperte

**Nessuna.** Tutte le decisioni sono chiuse: le quattro bloccanti emerse in Fase 2
(D-01…D-04) e le cinque rifiniture sollevate nella versione 1.0 di questo documento
(D-05…D-09, ex Q-01…Q-05). Si veda la tabella «Decisioni prese» al §3.

Restano invece **tre punti da rivalutare dopo il primo uso reale**, non prima:

- La soglia di 300 m (D-04), che sul campo potrebbe rivelarsi troppo generosa o troppo
  stretta.
- La granularità `zoom=10` (D-07) sui giri urbani, dove il sommario si riduce a una voce.
- Il perimetro del ritentativo automatico (D-06), se dovesse risultare più rumoroso del
  previsto.

---

## Esito dell'implementazione (2026-08-11)

Implementata nella stessa giornata in cui sono state chiuse le decisioni.

### Cosa è stato scritto

**Nuovi** — `SavedRide.kt` (modello, formato del file, `FinishedRide.toSavedRide()`),
`RideStore.kt`, `RouteDigest.kt`, `PlaceResolver.kt` (interfaccia, catena, cache,
`firstNonBlank`), `SystemGeocoder.kt`, `NominatimGeocoder.kt`, `RideArchivist.kt`,
`SaveRideDialog.kt`, `HistoryScreen.kt`, `Format.kt`,
`res/xml/data_extraction_rules.xml`, più i test `RouteDigestTest`, `SavedRideTest`,
`PlaceResolverTest`.

**Modificati** — `RideTracker.kt` (`FinishedRide`, `pendingRide`, `onStop(endedAtMillis)`,
`onRideHandled()`, `GeoPoint` serializzabile), `MainActivity.kt`, `RidePanel.kt` (solo
l'estrazione delle formattazioni), `AndroidManifest.xml`, `strings.xml`,
`libs.versions.toml`, i due `build.gradle.kts`, `RideTrackerTest.kt`, `CLAUDE.md`.

### Scostamenti dal piano

| # | Scostamento | Perché |
|---|---|---|
| 1 | **`SavedRide.pendingSamples`**: i punti campionati restano su disco finché i nomi non sono risolti, poi si svuotano | Buco del piano trovato scrivendo T-13: il ritentativo di D-06 ha bisogno di punti da rigeocodificare, ma il tracciato a fine giro è già sparito e non viene persistito. Senza questo campo il ritentativo automatico era semplicemente impossibile. Costo: ~40 coordinate per giro, e solo per i giri in attesa. |
| 2 | **Salvataggio su scope di processo** (`RideArchivist.save`) invece dello scope della composizione | Con `rememberCoroutineScope()` una rotazione dello schermo subito dopo il tap su *Salva* avrebbe cancellato la scrittura, perdendo esattamente il giro che la feature esiste per non perdere. L'oggetto orchestratore si chiama `RideArchivist` e non `RidePlaces` perché ora fa due cose: archivia e risolve. |
| 3 | **`android:dataExtractionRules` oltre a `allowBackup="false"`** | Segnalato dal lint: da Android 12 `allowBackup` non copre il trasferimento device-to-device. Senza le regole esplicite lo storico sarebbe uscito dal telefono al cambio di dispositivo, contro D-02. Il warning residuo che chiede anche `fullBackupContent` è silenziato con `tools:ignore` e una spiegazione: sotto Android 12 il backup è già spento in blocco. |
| 4 | **Nessun campo `id`**: l'identità del giro è `endedAtMillis` | Due STOP non cadono nello stesso millisecondo; una chiave in più sarebbe stata solo una cosa in più da tenere coerente. |
| 5 | **`avgSpeedKmh` derivata**, non salvata | Come in `RideState`. Salvarla avrebbe introdotto un dato ridondante che può contraddire gli altri due. |
| 6 | **`TrackingService` non modificato** | L'istante di fine arriva dal valore di default di `onStop()`. Il service non aveva niente da aggiungere. |
| 7 | **`RideStore.updatePlaces` senza parametro `resolved`** | Si scrive solo quando la risoluzione è riuscita: il caso «fallita» è l'assenza di scrittura, e un flag sempre `true` sarebbe stato rumore. |
| 8 | **`Format.kt` contiene più di `formatElapsed`** | Estraendo il solo cronometro, distanza e velocità sarebbero rimaste duplicate fra pannello, dialog e storico. |
| 9 | **Pulsante «Storico» visibile solo a giro fermo** | Durante il tracciamento passare allo storico smonterebbe la `MapView`, e i giri passati si guardano da fermi. |
| 10 | **Stima**: nessuna revisione | Il lavoro è stato completato in una sessione, ma la stima di 8,75 gg/uomo resta quella del piano: non è una misura di quanto ci ha messo un assistente. |

### Verifiche eseguite

- `./gradlew testDebugUnitTest` — **53 test verdi** (erano 17): 16 `RideTracker`,
  12 `RouteDigest`, 12 `PlaceResolver`, 8 `SavedRide`, 5 `CalorieModel`.
- `./gradlew lintRelease` — **BUILD SUCCESSFUL, 0 errori, 15 warning**, tutti preesistenti
  (`InlinedApi` in `TrackingService`, versioni obsolete di AGP e dipendenze, `UseKtx` sui
  due store a preferenze, `ObsoleteSdkInt` su `mipmap-anydpi-v26`). Il warning
  `DataExtractionRules` introdotto dalla feature è stato risolto, non silenziato a caso.
- `./build.sh` — APK debug prodotto.

### Non verificato

- **`./build.sh release`**: richiede le credenziali del keystore, non disponibili in
  questa sessione. Il codice compila in release (`compileReleaseKotlin` gira dentro
  `lintRelease`) ma **la build firmata va lanciata a mano**.
- **Tutti i test manuali TC-08…TC-13**: nessun emulatore né telefono in questa sessione.
  In particolare restano da provare il ramo Nominatim con `./demo-ride.sh`, il rientro
  dallo storico con la mappa viva (TC-11, il più a rischio) e un giro reale su telefono
  con GMS per il ramo primario.
- **Il throttling a 1 richiesta/secondo non è coperto da unit test**: dipende
  dall'orologio reale e servirebbe iniettare un clock in `NominatimGeocoder`. Il codice
  c'è ed è un mutex tenuto per tutta la chiamata, ma la garanzia è per ora solo per
  ispezione.
- **`kotlinx-serialization-json` è alla 1.8.0** e il lint segnala che esiste la 1.11.0.
  Non aggiornata di proposito: il progetto è su Kotlin 2.1.20 e la 1.8.0 è la scelta
  compatibile. Il warning si aggiunge agli otto già presenti per le altre dipendenze.

---

*Documento generato con la skill `claude-code-feature` — Fase 3.*
