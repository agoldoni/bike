# view-on-map — Fase 1: Requisiti

**Feature:** `view-on-map`
**Data:** 2026-07-26
**Progetto:** Bike (app Android nativa, Kotlin + Jetpack Compose)

---

## 1. Obiettivo e motivazione

Oggi la schermata dell'app mostra solo numeri: velocità istantanea, distanza, tempo e
velocità media. Il ciclista non ha alcun riscontro visivo di *dove* si trova né di
*quale percorso* sta facendo, e non può verificare a colpo d'occhio se il GPS sta
agganciando correttamente la posizione.

La feature aggiunge una mappa OpenStreetMap che occupa la parte principale dello
schermo e mostra la posizione corrente, spostando i dati numerici in un pannello nella
parte inferiore. Il pannello è espandibile con un tocco, così il ciclista può scegliere
fra due modalità d'uso: *navigazione* (mappa grande, dati ridotti) e *prestazione*
(dati grandi e leggibili in movimento, mappa ridotta).

La scelta di OpenStreetMap evita la dipendenza da Google Maps SDK e dalla relativa
API key con quota a consumo: le tile OSM sono gratuite e la libreria consente il
caching su disco, utile in zone con copertura dati scarsa.

---

## 2. Scope

### Incluso

- Mappa OpenStreetMap a schermo pieno come sfondo della schermata principale.
- Marker della posizione corrente, aggiornato a ogni campione GPS accettato.
- Centratura automatica della mappa sulla posizione corrente durante il tracciamento.
- Tracciato (polilinea) del percorso già compiuto nel giro corrente.
- Pannello dati inferiore in due stati: **compatto** (default) ed **espanso**
  (metà schermo inferiore).
- Transizione fra i due stati: tap sul pannello lo espande, tap sulla mappa (area
  superiore) lo riporta compatto. La transizione è animata.
- Il pulsante START/STOP resta sempre raggiungibile in entrambi gli stati.
- Conservazione dello stato (mappa, tracciato, stato del pannello) alla rotazione
  dello schermo e al ritorno da background.

### Escluso (out of scope)

- **Navigazione turn-by-turn e calcolo percorsi**: fuori dallo scopo di un ciclocomputer;
  richiederebbe un servizio di routing esterno.
- **Mappe offline scaricabili per area**: il caching automatico delle tile visitate è
  incluso di default nella libreria, ma il download preventivo di aree è una feature
  a sé, con gestione dello spazio disco e UI dedicata.
- **Storico dei giri e replay su mappa**: dipende dalla persistenza dei giri, che oggi
  non esiste (i dati vivono solo in memoria di processo). Va pianificato come feature
  separata e prerequisita.
- **Esportazione GPX/condivisione del tracciato**: dipende anch'essa dalla persistenza.
- **Layer alternativi** (satellite, ciclabili OpenCycleMap): richiedono API key di
  provider terzi; il layer standard OSM è sufficiente per la prima versione.
- **Bussola / rotazione della mappa secondo la direzione di marcia**: da valutare dopo
  il feedback sull'uso reale, aggiunge complessità e consumo batteria.

---

## 3. User Stories

### US-001 · Vedere la propria posizione sulla mappa
Come ciclista voglio vedere dove mi trovo su una mappa mentre pedalo, per orientarmi
in zone che non conosco senza dover aprire un'altra app.

### US-002 · Vedere il percorso già fatto
Come ciclista voglio vedere disegnato il tracciato del giro in corso, per capire a
colpo d'occhio la strada che ho già percorso ed evitare di ripassarci.

### US-003 · Ingrandire i dati con un tocco
Come ciclista in movimento voglio ingrandire il pannello dei dati con un solo tocco,
per leggere velocità e distanza senza fermarmi e senza precisione di mira, e poter
tornare alla mappa grande toccando la mappa stessa.

### US-004 · Verificare che il GPS stia funzionando
Come ciclista voglio capire subito se la posizione è stata agganciata, per non
accorgermi a fine giro che la traccia era vuota.

---

## 4. Criteri di accettazione

### US-001 — Posizione sulla mappa
- [ ] All'apertura dell'app la mappa OSM è visibile e occupa l'area sopra il pannello dati.
- [ ] Durante il tracciamento un marker indica la posizione corrente.
- [ ] La mappa si ricentra automaticamente sulla posizione a ogni aggiornamento.
- [ ] Se l'utente trascina la mappa manualmente, la ricentratura automatica si sospende;
      un pulsante "ricentra" la riattiva.
- [ ] Le tile si caricano solo con rete disponibile; senza rete la mappa mostra le tile
      già in cache senza andare in crash né bloccare il tracciamento.

### US-002 — Tracciato del percorso
- [ ] Ogni campione GPS accettato dal servizio aggiunge un punto alla polilinea.
- [ ] La polilinea è visibile e distinguibile dallo sfondo della mappa.
- [ ] Premendo START dopo uno STOP il tracciato precedente viene azzerato.
- [ ] Il tracciato sopravvive alla rotazione dello schermo.

### US-003 — Pannello espandibile
- [ ] In stato compatto il pannello mostra velocità istantanea, distanza, tempo e media.
- [ ] Un tap in un punto qualsiasi del pannello lo espande fino a metà schermo.
- [ ] In stato espanso i valori sono resi con caratteri più grandi.
- [ ] Un tap sull'area della mappa riporta il pannello allo stato compatto.
- [ ] La transizione fra i due stati è animata e dura meno di 400 ms.
- [ ] Il pulsante START/STOP è visibile e utilizzabile in entrambi gli stati.
- [ ] Lo stato del pannello sopravvive alla rotazione dello schermo.

### US-004 — Feedback aggancio GPS
- [ ] Prima del primo fix valido la UI segnala l'attesa del segnale.
- [ ] All'arrivo del primo fix l'indicazione sparisce e il marker compare.

---

## 5. Rischi e dipendenze

| Rischio / dipendenza | Note |
|---|---|
| **Tile usage policy di OSM** | I server tile pubblici richiedono uno User-Agent identificativo e vietano usi massivi. Va impostato uno User-Agent con l'applicationId, altrimenti si rischia il blocco degli IP. |
| **Consumo di batteria e dati** | Il rendering continuo della mappa più il download delle tile aumentano il consumo durante un giro lungo, proprio quando l'utente non può ricaricare. Va misurato su un giro reale. |
| **Interoperabilità View/Compose** | Le librerie OSM per Android sono basate su `View`: serve `AndroidView` e una gestione esplicita del ciclo di vita, che è la sorgente di bug più comune (memory leak, mappa che non riprende dopo il background). |
| **Crescita illimitata del tracciato** | Un giro lungo produce migliaia di punti; disegnarli tutti a ogni frame degrada il frame rate. Serve una politica di semplificazione o un limite. |
| **Stato solo in memoria** | `RideTracker` è un singleton di processo senza persistenza: se Android uccide il processo durante il giro, tracciato e statistiche si perdono. La feature non peggiora la situazione, ma la rende più visibile all'utente. |
| **Dimensione dell'APK** | La libreria mappa aggiunge alcuni MB al pacchetto. |

---

## 6. Stima effort

Progetto a singolo sviluppatore, nessun backend coinvolto.

| Area | Attività | Stima (gg/uomo) |
|---|---|---|
| FE | Integrazione libreria mappa, ciclo di vita, marker e centratura | 1,5 |
| FE | Polilinea del tracciato e gestione dei punti | 0,5 |
| FE | Pannello dati espandibile con animazione e gestione dei tap | 1,0 |
| Core | Esposizione della posizione e del tracciato nello stato condiviso | 0,5 |
| Test | Unit test sullo stato, verifica manuale su emulatore e telefono | 0,5 |
| Doc | Aggiornamento documentazione e note sulla usage policy OSM | 0,25 |
| **Totale** | | **4,25 gg** |

La stima non include le voci fuori scope (persistenza, mappe offline, export GPX).

---

## 7. Milestones

1. **M1 — Dipendenza e mappa statica**: aggiunta della libreria al version catalog,
   permesso `INTERNET`, mappa visibile a schermo pieno con ciclo di vita corretto.
2. **M2 — Posizione corrente**: lo stato condiviso espone la posizione; marker sulla
   mappa e centratura automatica.
3. **M3 — Tracciato**: accumulo dei punti del giro e disegno della polilinea, con
   azzeramento al nuovo START.
4. **M4 — Pannello dati**: estrazione dei riquadri numerici in un pannello inferiore,
   due stati con animazione e gestione dei tap.
5. **M5 — Rifiniture**: indicazione di attesa GPS, pulsante di ricentratura, tenuta
   alla rotazione.
6. **M6 — Verifica sul campo**: giro reale con misura di consumo batteria e dati,
   controllo della fluidità con tracciato lungo.

---

*Documento generato con la skill `claude-code-feature` — Fase 1.*
