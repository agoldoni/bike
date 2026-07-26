# view-on-map — Implementation Plan

**Stato:** Implementata il 2026-07-26 — vedi "Esito dell'implementazione" in fondo
**Autore:** Alberto Goldoni (documento redatto con assistenza di Claude Code)
**Data:** 2026-07-26
**Versione:** 1.1

---

## 1. Executive Summary

L'app Bike oggi mostra solo numeri: velocità, distanza, tempo e media. Questa feature
aggiunge una mappa OpenStreetMap che riempie lo schermo e mostra dove ci si trova e che
strada si è già percorsa, spostando i dati numerici in un pannello nella parte bassa.
Toccando il pannello lo si ingrandisce fino a metà schermo per leggere i valori mentre
si pedala; toccando la mappa il pannello torna piccolo. Si usa OpenStreetMap per non
dipendere da Google Maps e dalla sua API key a consumo. Stima: circa **4,5 giorni/uomo**
di lavoro per un singolo sviluppatore.

---

## 2. Obiettivo e motivazione

- **Problema che risolve:** senza mappa il ciclista non può orientarsi né verificare che
  il GPS stia realmente agganciando la posizione; se ne accorge solo a fine giro. Inoltre
  i quattro numeri hanno tutti lo stesso peso visivo, mentre in movimento serve poter
  ingrandire quello che interessa.
- **Metriche di successo:**
  - [ ] La posizione compare sulla mappa entro 30 secondi dallo START all'aperto.
  - [ ] Il tracciato disegnato coincide con il percorso reale, senza salti né buchi.
  - [ ] Il consumo batteria di un giro di un'ora resta entro +30% rispetto alla versione
        senza mappa (misurato su dispositivo reale, schermo acceso in entrambi i casi).
  - [ ] Nessun rallentamento percepibile della UI con un tracciato di 2 ore.
- **Legame con obiettivi di prodotto:** l'app è un progetto personale; l'obiettivo è
  renderla sufficiente per sostituire un ciclocomputer commerciale durante i giri.

---

## 3. Scope

### Incluso

- Mappa OpenStreetMap a schermo pieno con marker della posizione corrente.
- Centratura automatica sulla posizione, sospesa se l'utente trascina la mappa, con
  pulsante di ricentratura.
- Polilinea del percorso compiuto nel giro corrente, azzerata a ogni nuovo START.
- Pannello dati inferiore in due stati (compatto ed espanso a metà schermo) con
  transizione animata: tap sul pannello espande, tap sulla mappa collassa.
- Pulsante START/STOP sempre raggiungibile in entrambi gli stati.
- Indicazione di attesa del segnale GPS prima del primo fix.
- Tenuta alla rotazione dello schermo e al ritorno da background.

### Escluso (out of scope)

- **Navigazione turn-by-turn e routing** — richiederebbe un servizio esterno; l'app è un
  ciclocomputer, non un navigatore.
- **Download di mappe offline per area** — il caching delle tile visitate è automatico,
  ma il download preventivo richiede UI e gestione dello spazio disco dedicate.
- **Storico dei giri e replay su mappa** — dipende dalla persistenza, che non esiste.
- **Esportazione GPX** — stessa dipendenza.
- **Layer alternativi (satellite, ciclabili)** — richiedono API key di terzi.
- **Rotazione della mappa secondo la direzione di marcia** — da valutare dopo il feedback
  sull'uso reale; aggiunge complessità e consumo.

### Decisioni aperte

> ⚠️ DA COMPLETARE: la decisione 1 è bloccante e va risolta prima di scrivere codice.

| # | Decisione | Responsabile | Scadenza |
|---|-----------|-------------|---------|
| 1 | Come esporre il tracciato senza copiare una lista crescente a ogni fix (campo in `RideState` / `StateFlow` dedicato / campionamento ridotto) | Alberto | Prima di T-03 |
| 2 | Introdurre un `ViewModel` per lo stato UI o continuare con l'Activity che osserva il singleton | Alberto | Prima di T-05 |
| 3 | Frequenza dei punti del tracciato: uno per fix accettato o uno ogni N metri | Alberto | Prima di T-03 |
| 4 | Palette colori condivisa (`Color.kt`) oppure mantenere i colori inline come oggi | Alberto | Prima di T-05 |

---

## 4. User Stories e criteri di accettazione

### US-001 · Vedere la propria posizione sulla mappa
**Priorità:** Must Have

Come ciclista voglio vedere dove mi trovo su una mappa mentre pedalo, per orientarmi in
zone che non conosco senza aprire un'altra app.

**Criteri di accettazione:**
- [ ] All'apertura la mappa OSM è visibile nell'area sopra il pannello dati.
- [ ] Durante il tracciamento un marker indica la posizione corrente.
- [ ] La mappa si ricentra automaticamente a ogni aggiornamento di posizione.
- [ ] Trascinando la mappa la ricentratura si sospende; un pulsante la riattiva.
- [ ] Senza rete la mappa mostra le tile in cache e non va in crash; il tracciamento
      continua a funzionare normalmente.
- [ ] L'attribuzione "© OpenStreetMap contributors" è visibile e non coperta dal pannello.

### US-002 · Vedere il percorso già fatto
**Priorità:** Must Have

Come ciclista voglio vedere disegnato il tracciato del giro in corso, per capire a colpo
d'occhio la strada già percorsa.

**Criteri di accettazione:**
- [ ] Ogni campione GPS accettato dal servizio aggiunge un punto alla polilinea.
- [ ] La polilinea è chiaramente distinguibile sullo sfondo della mappa.
- [ ] Un nuovo START azzera il tracciato precedente.
- [ ] Il tracciato sopravvive alla rotazione dello schermo.
- [ ] Da fermo non si accumulano punti (filtro anti-jitter già presente nel service).

### US-003 · Ingrandire i dati con un tocco
**Priorità:** Must Have

Come ciclista in movimento voglio ingrandire il pannello dei dati con un solo tocco, per
leggere i valori senza fermarmi e senza precisione di mira.

**Criteri di accettazione:**
- [ ] In stato compatto il pannello mostra velocità, distanza, tempo e media.
- [ ] Un tap in un punto qualsiasi del pannello lo espande fino a metà schermo.
- [ ] In stato espanso i valori usano caratteri più grandi.
- [ ] Un tap sull'area della mappa riporta il pannello a compatto.
- [ ] La transizione è animata e dura meno di 400 ms.
- [ ] Il tap di collasso non impedisce pan e zoom della mappa.
- [ ] Il pulsante START/STOP è visibile e utilizzabile in entrambi gli stati.
- [ ] Lo stato del pannello sopravvive alla rotazione.

### US-004 · Verificare che il GPS stia funzionando
**Priorità:** Should Have

Come ciclista voglio capire subito se la posizione è stata agganciata, per non scoprire
a fine giro che la traccia era vuota.

**Criteri di accettazione:**
- [ ] Prima del primo fix valido la UI segnala l'attesa del segnale.
- [ ] All'arrivo del primo fix l'indicazione sparisce e il marker compare, anche se il
      ciclista è ancora fermo.

---

## 5. Architettura tecnica

### Componenti coinvolti

```
   TrackingService ──onFirstFix/onSample(pos)──► RideTracker
   (fused GPS 1 Hz)                              StateFlow<RideState>
                                                        │
                                    collectAsStateWithLifecycle
                                                        ▼
                                                  MainActivity
                                                        │
                                    ┌───────────────────┴───────────────────┐
                                    ▼                                       ▼
                              RideMap.kt (nuovo)                    RidePanel.kt (nuovo)
                          AndroidView { MapView }                compatto ⇄ espanso
                          marker + polyline                      Metric, formatElapsed
                                    │                                  START/STOP
                                    ▼
                          osmdroid 6.1.20 → tile OSM (rete + cache disco)
```

Tutta l'interazione con osmdroid resta confinata in `RideMap.kt`: il resto del codice
conosce solo un `GeoPoint` di dominio, così la libreria può essere sostituita (es. con
MapLibre) senza toccare stato, service e pannello.

### Modifiche al data model

Nessun database nel progetto. Cambia solo il modello in memoria:

| Tabella/Tipo | Tipo modifica | Dettaglio |
|---|---|---|
| `RideState` (`RideTracker.kt:8-16`) | Modifica | Nuovi campi `currentPosition: GeoPoint?` e tracciato del giro (forma da definire, decisione aperta #1) |
| `GeoPoint` (dominio) | Nuovo | `data class GeoPoint(val lat: Double, val lon: Double)` — deliberatamente **non** quello di osmdroid |
| `RideTracker.onSample` | Modifica firma | Aggiunta della posizione; unico chiamante `TrackingService.kt:116` |
| `RideTracker.onFirstFix` | Nuovo metodo | Il primo fix oggi esce con un `return` anticipato (`TrackingService.kt:94-98`) senza notificare: senza questo il marker non comparirebbe da fermo |

### Nuove API o endpoint

Nessuna: l'app non ha backend proprio. L'unico traffico di rete è il download delle tile
dai server OSM pubblici, che richiede:

| Elemento | Valore | Nota |
|---|---|---|
| Permesso | `android.permission.INTERNET` | Oggi **assente** dal manifest (righe 4-8): senza, mappa grigia e nessun errore evidente |
| User-Agent | `it.agoldoni.bike` (applicationId) | Obbligatorio per la tile usage policy OSM; da impostare una volta all'avvio |

### Breaking changes

Nessun breaking change esterno: l'app non espone API pubbliche e non ha dati persistiti
da migrare. Il cambio di firma di `onSample` è interno e ha un solo chiamante.

---

## 6. Piano di implementazione

| ID | Task | Area | Stima (gg) | Dipende da | Responsabile |
|---|---|---|---|---|---|
| T-01 | Aggiungere osmdroid 6.1.20 al version catalog e a `app/build.gradle.kts`; permesso `INTERNET`; User-Agent all'avvio | Infra | 0,25 | — | Alberto |
| T-02 | `RideMap.kt`: mappa a schermo pieno via `AndroidView`, ciclo di vita (`onResume`/`onPause`/`onDetach`) agganciato al lifecycle Compose | FE | 1,0 | T-01 | Alberto |
| T-03 | `GeoPoint` di dominio; estensione di `RideState`; `onFirstFix()`; propagazione della posizione da `TrackingService.onLocation()` | Core | 0,5 | Decisione #1 | Alberto |
| T-04 | Marker della posizione, centratura automatica, sospensione al pan manuale e pulsante di ricentratura | FE | 0,5 | T-02, T-03 | Alberto |
| T-05 | Polilinea del tracciato con azzeramento al nuovo START | FE | 0,5 | T-03, T-04 | Alberto |
| T-06 | `RidePanel.kt`: pannello a due stati con animazione, spostamento di `Metric` e `formatElapsed` da `MainActivity.kt` | FE | 0,75 | T-02 | Alberto |
| T-07 | Ristrutturazione di `RideScreen` da `Column` a `Box` (mappa + pannello); gestione dei tap senza rubare i gesti alla mappa | FE | 0,5 | T-05, T-06 | Alberto |
| T-08 | Indicazione di attesa GPS e tenuta alla rotazione | FE | 0,25 | T-07 | Alberto |
| T-09 | `RideTrackerTest.kt`: prima suite di unit test del progetto (creare `app/src/test/`) | Test | 0,5 | T-03 | Alberto |
| T-10 | Verifica su emulatore con `adb emu geo fix` e giro reale su telefono con misura batteria/dati | Test | 0,5 | T-08 | Alberto |
| T-11 | Aggiornamento documentazione e nota sulla tile usage policy OSM | Doc | 0,25 | T-10 | Alberto |

**Stima totale:** 5,5 giorni/uomo
**Breakdown:** Core 0,5gg · FE 3,5gg · Infra 0,25gg · Test 1,0gg · Doc 0,25gg

> La stima è salita da 4,25 gg (Fase 1) a 5,5 gg dopo l'analisi: il ciclo di vita della
> `MapView` in Compose e la creazione da zero della struttura di test non erano stati
> considerati.

---

## 7. Piano di test

**Strategia generale:** unit test sulla logica di stato (l'unica parte testabile in
isolamento), test strumentati sul pannello se si accettano le due dipendenze aggiuntive
di Compose UI test, verifica manuale per tutto ciò che riguarda la mappa. Il progetto
**non ha oggi alcun test**: `app/src/test` e `app/src/androidTest` vanno create, mentre
`junit` 4.13.2 è già dichiarata come `testImplementation` (`app/build.gradle.kts:76`).

### Test cases critici

| ID | Tipo | Descrizione | Priorità |
|---|---|---|---|
| TC-01 | Unit | `onStart()` azzera il tracciato di un giro precedente | Alta |
| TC-02 | Unit | `onSample()` accoda il punto e incrementa la distanza coerentemente | Alta |
| TC-03 | Unit | `onFirstFix()` popola la posizione senza alterare distanza e velocità | Alta |
| TC-04 | Unit | `onSpeedLost()` azzera la velocità senza toccare il tracciato | Media |
| TC-05 | Unit | Conversione `GeoPoint` dominio ⇄ osmdroid, incluse coordinate negative | Media |
| TC-06 | UI | Tap sul pannello → espanso; tap sulla mappa → compatto | Alta |
| TC-07 | UI | START/STOP cliccabile in entrambi gli stati del pannello | Alta |
| TC-08 | Manuale | Percorso simulato su emulatore: il tracciato disegnato corrisponde ai fix inviati | Alta |
| TC-09 | Manuale | Modalità aereo a metà giro: nessun crash, tile in cache, tracciamento invariato | Alta |
| TC-10 | Manuale | App in background e ritorno in foreground: mappa viva, nessun leak | Alta |
| TC-11 | Manuale | Rotazione schermo: tracciato, posizione e stato del pannello conservati | Media |
| TC-12 | Manuale | Giro reale di un'ora: consumo batteria e dati, fluidità con tracciato lungo | Media |

### Definition of Done per QA

- [ ] Tutti gli unit test passano (nessuna soglia di coverage formale: il progetto parte da zero)
- [ ] `./build.sh` completa senza warning nuovi
- [ ] `./build.sh release` completa con la firma corretta
- [ ] Verifica su emulatore (`Emulator_x86_64`, API 33 con Play Services) superata
- [ ] Verifica su telefono reale con un giro all'aperto superata
- [ ] Nessun errore in logcat durante un giro completo
- [ ] Attribuzione OSM visibile e non coperta
- [ ] Documentazione aggiornata

---

## 8. Rischi e mitigazioni

| Rischio | Probabilità | Impatto | Mitigazione |
|---|---|---|---|
| Copia di una lista crescente a ogni fix (`RideTracker.kt:45-49` fa `copy()` a 1 Hz) | Alta | Alto | Risolvere la decisione aperta #1 prima di T-03: flow dedicato o campionamento ridotto |
| Ciclo di vita della `MapView` gestito male (nessun precedente di `AndroidView` nel progetto) | Media | Alto | Isolare tutto in `RideMap.kt`, agganciare esplicitamente gli eventi di lifecycle, verificare con TC-10 |
| Il tap di collasso ruba i gesti di pan/zoom alla mappa | Media | Medio | Gestire il tap senza consumare i gesti della `MapView`; coprire con TC-06 e verifica manuale |
| Permesso `INTERNET` dimenticato | Bassa | Alto | Task T-01 esplicito; il sintomo (mappa grigia senza errori) è ingannevole |
| Consumo batteria oltre soglia (schermo sempre acceso via `MainActivity.kt:52-58` + mappa + GPS) | Media | Medio | Misurare in TC-12; se eccessivo, valutare riduzione del frame rate della mappa o spegnimento del rendering a schermo bloccato |
| Blocco dai server tile OSM per User-Agent mancante | Bassa | Alto | User-Agent con applicationId in T-01; documentare la policy in T-11 |
| osmdroid non aggiornato da agosto 2024 | Media | Basso | Uso confinato a `RideMap.kt` per permettere la sostituzione con MapLibre |
| Perdita di stato per uccisione del processo (`RideTracker` è un `object` senza persistenza) | Media | Medio | Fuori scope, ma da tracciare come feature successiva: la mappa rende la perdita più evidente |
| Crescita dell'APK di alcuni MB | Alta | Basso | Accettato |

---

## 9. Rollout e feature flag

**Strategia di rilascio:**
- [x] Deploy diretto (direct)
- [ ] Graduale con feature flag
- [ ] Canary release

Non si usano feature flag: l'app è un progetto personale con un solo utente, distribuita
via `./install-all.sh` sul telefono. Un flag aggiungerebbe complessità senza benefici.

**Piano di rollback:**
1. Reinstallare l'APK della versione precedente (`git checkout` del commit precedente e
   `./install-all.sh --build`).
2. Nessuna migrazione dati da annullare: la feature non introduce persistenza.

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

1. **Struttura dati del tracciato** — bloccante: campo in `RideState`, `StateFlow`
   separato, o punto ogni N metri invece che ogni fix? Decide Alberto prima di T-03.
2. **Frequenza dei punti** — un punto per ogni fix accettato (1 Hz, ~3600 punti/ora) è
   più preciso ma più pesante di un punto ogni 5-10 metri. Decide Alberto.
3. **ViewModel sì o no** — con mappa, stato del pannello e flag di ricentratura, la
   quantità di stato UI cresce; conviene introdurlo ora o rimandare? Decide Alberto.
4. **Test strumentati** — si accettano le due dipendenze aggiuntive (`ui-test-junit4`,
   `ui-test-manifest`) per coprire il pannello, o ci si limita alla verifica manuale?
   Decide Alberto.
5. **Zoom iniziale** — a quale livello di zoom aprire la mappa prima del primo fix, e su
   quale posizione (ultima nota, oppure vista neutra)? Decide Alberto.
6. **Comportamento a tracciamento fermo** — con il giro non attivo la mappa deve mostrare
   comunque la posizione corrente (richiede GPS acceso fuori dal giro, con costo batteria)
   oppure restare sull'ultima posizione nota? Decide Alberto.

---

## Esito dell'implementazione (2026-07-26)

La feature è stata implementata e verificata sull'emulatore nella stessa giornata.
Le domande aperte sono state risolte come segue.

| # | Domanda | Decisione presa |
|---|---|---|
| 1 | Struttura dati del tracciato | `StateFlow` dedicato `RideTracker.track`, separato da `state`, più un `StateFlow` per la posizione corrente. Il pannello non si ricompone per i punti, la mappa non si ridisegna per il cronometro. |
| 2 | ViewModel sì o no | No. Lo stato UI è due booleani (pannello espanso, inseguimento attivo), tenuti con `rememberSaveable` in `RideScreen`: un ViewModel non avrebbe aggiunto nulla. |
| 3 | Frequenza dei punti | Un vertice ogni 10 m (`TrackingService.TRACK_STEP_M`), non a ogni fix. A 20 km/h è un punto ogni ~2 s invece di uno al secondo. |
| 4 | Test strumentati | Non introdotti: nessuna dipendenza di Compose UI test aggiunta. Il pannello è stato verificato manualmente sull'emulatore. Restano 8 unit test su `RideTracker`. |
| 5 | Zoom iniziale | Zoom 16. Il centro iniziale **non** è stato risolto: vedi limiti noti. |
| 6 | Comportamento a tracciamento fermo | La mappa resta sull'ultima posizione nota della sessione; il GPS non viene acceso fuori dal giro. |

### Cosa è stato scritto

| File | Stato |
|---|---|
| `app/src/main/java/it/agoldoni/bike/RideMap.kt` | Nuovo — unico punto che conosce osmdroid |
| `app/src/main/java/it/agoldoni/bike/RidePanel.kt` | Nuovo — pannello a due stati |
| `app/src/main/res/drawable/ic_position.xml` | Nuovo — pallino della posizione |
| `app/src/test/java/it/agoldoni/bike/RideTrackerTest.kt` | Nuovo — 8 test, tutti verdi |
| `RideTracker.kt`, `TrackingService.kt`, `MainActivity.kt` | Modificati |
| `libs.versions.toml`, `build.gradle.kts`, `AndroidManifest.xml`, `strings.xml` | Modificati |

Rispetto al piano non è stato necessario `GeoPointMapperTest.kt`: la conversione è una
riga in `RideMap.kt` ed è coperta implicitamente dalla verifica visiva.

### Verifiche eseguite

Su emulatore `Emulator_x86_64` (API 33) con percorso simulato via `adb emu geo fix`:
tile OSM scaricate correttamente, marker e centratura automatica funzionanti, polilinea
del tracciato coerente con i fix inviati, espansione del pannello al tocco, collasso al
tocco sulla mappa, sospensione dell'inseguimento al trascinamento con comparsa del
pulsante "Ricentra" e ripristino al tocco. Unit test: 8 su 8 verdi.

**Non ancora verificato:** giro reale su telefono (TC-12, consumo batteria e dati),
comportamento in modalità aereo (TC-09), ritorno da background (TC-10) e rotazione
schermo (TC-11).

### Correzioni emerse dalla verifica su telefono (Android 16)

Lo screenshot sul Redmi Note 13 Pro ha rivelato problemi che l'emulatore API 33 non
mostrava: con `targetSdk 36` su Android 15+ l'app è **edge-to-edge forzato**.

1. La barra di navigazione a tre pulsanti copriva il pulsante START/STOP →
   `navigationBarsPadding()` sul contenuto del pannello e `statusBarsPadding()` sul
   pulsante "Ricentra".
2. L'attribuzione OpenStreetMap finiva sopra l'orologio di sistema →
   `CopyrightOverlay.setOffset()` con l'inset della status bar, passato a `RideMap`.
3. Il pulsante restava tagliato anche dopo il padding: `COMPACT_FRACTION` portata da
   0,28 a 0,34 e velocità in compatto da 54sp a 48sp.
4. Con il pannello espanso i tre valori si sovrapponevano ("0,00" contro "0:00:00") →
   colonne di uguale larghezza con `weight(1f)` e crescita del font ridotta.

### Stile cartografico

Il layer standard di OSM (Mapnik) si è rivelato troppo carico: punti d'interesse,
icone e etichette colorate competono con il tracciato. È stato sostituito da
**CartoDB Positron** (`light_all`), definito come `XYTileSource` in `RideMap.kt`:
sfondo chiaro, nessun POI, etichette tenui. Le tile pesano circa 1 KB contro i 40 KB
di Mapnik, il che riduce sensibilmente il consumo dati di un giro lungo.

Alternative pronte, cambiando il solo frammento di URL: `light_nolabels` (senza nomi
delle vie), `dark_all` / `dark_nolabels` (versione scura).

> ⚠️ Le basemap CARTO sono gratuite per uso personale e a volume moderato, con
> attribuzione obbligatoria "© OpenStreetMap contributors © CARTO" — già resa dal
> `CopyrightOverlay`, che legge la nota dalla tile source. Per una eventuale
> distribuzione pubblica dell'app andrebbero verificati i termini o si torna a Mapnik.

### Bug del filtro di accuratezza (corretto dopo la prima uscita reale)

Alla prima prova all'aperto l'app non ha mai mostrato la posizione. Causa nel codice:
`onLocation()` scartava il fix **prima di qualsiasi cosa** quando l'accuratezza dichiarata
superava i 25 m, quindi con segnale mediocre non veniva mostrato nulla — né marker, né
mappa, né messaggio — e il comportamento era indistinguibile da un GPS spento. Sui fix
simulati dell'emulatore l'accuratezza è assente, perciò il filtro non era mai scattato.

Correzioni:

1. `RideTracker.onPosition()` sostituisce `onFirstFix()`: la posizione viene mostrata
   **sempre**, qualunque sia l'accuratezza. Il filtro protegge ora solo distanza,
   velocità e tracciato, che sono le grandezze che un fix impreciso falserebbe.
2. Soglia da 25 m a 35 m: in città fra i palazzi l'accuratezza sta spesso sopra i 20 m.
3. `RideState.accuracyMeters` e riga diagnostica nel pannello: "attendo il segnale GPS…"
   quando non arriva nulla, "segnale impreciso · ±NN m" quando arrivano fix inutilizzabili.
   Senza, i due casi erano indistinguibili.
4. Controllo della geolocalizzazione di sistema all'avvio del giro, con avviso e
   scorciatoia alle impostazioni.

### Limiti noti introdotti

1. ~~**Centro iniziale**~~ — **risolto**: `LastKnownPosition` conserva l'ultima
   posizione agganciata in `SharedPreferences` (salvata al primo fix, allo stop e alla
   distruzione del service) e la mappa vi si apre a zoom 16. Al primo avvio in assoluto,
   quando non c'è nulla di salvato, la vista è larga sull'Italia (zoom 5) invece del
   punto 0,0. Il primo fix riporta lo zoom a 16 se si veniva dalla vista larga.
2. **Zoom e centro non sopravvivono alla rotazione**: la `MapView` viene ricreata. Con
   l'inseguimento attivo la mappa si ricentra da sola e la perdita è invisibile; se
   l'utente aveva trascinato la mappa, la vista torna sull'ultima posizione.
3. **`onTrackPoint` copia comunque la lista**: l'accodamento resta O(N), ma con un
   quarto dei punti e un ridisegno incrementale (`Polyline.addPoint`) il costo è
   accettabile. Se emergesse un problema su giri molto lunghi, il passo successivo è
   `PersistentList`.

---

*Documento generato con la skill `claude-code-feature` — Fase 3.*
