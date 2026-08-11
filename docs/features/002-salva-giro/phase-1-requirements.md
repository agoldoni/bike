# salva-giro — Fase 1: Requisiti

**Feature:** `salva-giro`
**Data:** 2026-08-11
**Progetto:** Bike (app Android nativa, Kotlin + Jetpack Compose)

---

## 1. Obiettivo e motivazione

Oggi il giro vive solo in memoria di processo: `RideTracker` è un `object` singleton
senza persistenza e `onStart()` azzera stato, posizione e tracciato. Appena si preme
START una seconda volta — o appena Android uccide il processo — il giro precedente
sparisce senza che l'utente abbia avuto modo di conservarlo. È la lacuna che la feature
`001-view-on-map` aveva già dichiarato fuori scope, rimandandola a una feature
«separata e prerequisita»: questa.

La feature chiude il ciclo di vita del giro. Al tap su **STOP** l'app chiede se salvare
quanto appena percorso; se l'utente accetta, il giro finisce in uno storico consultabile
dentro l'app.

Del percorso **non si conserva la polilinea completa** ma i suoi *punti salienti*: le
città e le località attraversate, in ordine di percorrenza. La ragione è che un elenco di
qualche migliaio di coordinate non dice niente a chi lo rilegge fra un mese, mentre
«Modena → Formigine → Maranello → Modena» identifica il giro a colpo d'occhio. Il
sommario testuale è anche l'unica forma di storico che resta leggibile senza aprire una
mappa, occupa pochi byte per giro e non pone problemi di dimensione del file nel tempo.

Le località si ricavano per reverse geocoding di un campione dei punti del tracciato,
usando il `Geocoder` di sistema con ripiego su Nominatim (OpenStreetMap) quando il primo
non risponde — caso concreto e non teorico, visto che sulle immagini AOSP senza Google
Play services il geocoder di sistema resta muto.

---

## 2. Scope

### Incluso

- **Richiesta di salvataggio al tap su STOP**: dialog «Salvare questo giro?» con le
  statistiche del giro appena concluso e due azioni, *Salva* e *Non salvare*.
- **Soglia minima**: sotto una distanza/durata irrisoria (giro aperto per errore) la
  domanda non compare e il giro viene scartato in silenzio.
- **Estrazione dei punti salienti**: campionamento del tracciato e reverse geocoding dei
  punti campionati, con deduplica delle località consecutive uguali, per ottenere la
  sequenza ordinata dei comuni/frazioni attraversati.
- **Reverse geocoding a due sorgenti**: `android.location.Geocoder` di sistema come
  primaria, Nominatim OSM via HTTP come ripiego.
- **Persistenza dei giri salvati** su storage privato dell'app: statistiche (data e ora,
  durata, distanza, velocità media, kcal, massa usata) più la sequenza di località.
- **Schermata storico**: lista dei giri salvati, dal più recente, ognuno con data e
  sommario delle località; tap su un giro per vederne il dettaglio completo.
- **Cancellazione** di un giro dallo storico, con conferma.
- **Degrado esplicito senza rete**: se nessuna delle due sorgenti risponde il giro si
  salva comunque, con le sole statistiche, e la mancanza delle località è visibile
  all'utente invece di essere silenziosa.

### Escluso (out of scope)

- **Replay del tracciato su mappa**: conseguenza diretta della scelta di salvare solo i
  punti salienti. Se in futuro si vorrà rivedere il percorso disegnato servirà persistere
  anche la polilinea, con tutt'altro profilo di occupazione disco.
- **Esportazione GPX / condivisione del sommario**: dipende da questa feature ma è una
  superficie a sé (share sheet, formato di scambio, permessi di scrittura).
- **Salvataggio automatico e recupero di un giro interrotto** dalla morte del processo:
  richiede la persistenza incrementale *durante* il giro, non a fine giro. Va pianificato
  a parte.
- **Titolo o note del giro scritti a mano**: il sommario è generato, non editabile.
- **Statistiche aggregate** (totali settimanali/mensili, grafici, record personali).
- **Sincronizzazione cloud, account, backup fuori dal dispositivo.**
- **Modifica retroattiva dei pesi** su un giro già salvato.

---

## 3. User Stories

### US-001 · Non perdere il giro appena finito
Come ciclista voglio che a fine giro l'app mi chieda se salvarlo, per non scoprire alla
partenza successiva che il giro di ieri non esiste più.

### US-002 · Buttare via i giri di prova
Come ciclista voglio poter rispondere «non salvare», per non riempire lo storico con le
partenze fatte per errore o con le prove sotto casa.

### US-003 · Riconoscere un giro dalle località
Come ciclista voglio vedere elencate le città e le località che ho attraversato, per
capire di quale giro si tratta senza dover ricordare la data.

### US-004 · Rivedere i giri passati
Come ciclista voglio una schermata con l'elenco dei giri salvati, per confrontare
distanza, durata e calorie con le uscite precedenti.

### US-005 · Fare pulizia nello storico
Come ciclista voglio cancellare un giro dallo storico, per rimuovere le uscite che non mi
interessa conservare.

### US-006 · Salvare anche senza copertura dati
Come ciclista voglio che il giro si salvi comunque quando sono in una valle senza rete,
per non perdere le statistiche solo perché non si sono potuti risolvere i nomi dei paesi.

---

## 4. Criteri di accettazione

### US-001 — Richiesta di salvataggio
- [ ] Al tap su STOP il tracciamento si ferma e compare un dialog che chiede se salvare.
- [ ] Il dialog mostra le statistiche del giro appena concluso: durata, distanza,
      velocità media, kcal.
- [ ] Finché il dialog è aperto i dati del giro restano intatti e leggibili sotto.
- [ ] Toccando *Salva* il giro entra nello storico e il dialog si chiude.
- [ ] Un nuovo START mentre il dialog è aperto non è possibile, o se lo è azzera il giro
      solo dopo che l'utente ha risposto: la scelta non deve poter essere aggirata.
- [ ] Se il giro è più corto della soglia minima il dialog non compare affatto.

### US-002 — Scarto del giro
- [ ] Toccando *Non salvare* il dialog si chiude e nulla viene scritto su disco.
- [ ] Il giro scartato non compare nello storico.
- [ ] Il dialog non è chiudibile per sbaglio toccando fuori: serve una risposta esplicita.

### US-003 — Punti salienti del percorso
- [ ] Per un giro con tracciato non vuoto e rete disponibile, il giro salvato contiene
      almeno la località di partenza e quella di arrivo.
- [ ] Le località attraversate sono in ordine di percorrenza.
- [ ] La stessa località non compare due volte di fila (un giro dentro un solo comune
      produce una sola voce, non una per punto campionato).
- [ ] Una località riattraversata dopo esserne usciti può ricomparire (andata e ritorno
      mostra `A → B → A`).
- [ ] Il numero di richieste di geocoding per giro è limitato: non si geocodifica un punto
      per ogni vertice del tracciato.
- [ ] Il salvataggio non blocca la UI: il dialog si chiude subito e le località compaiono
      appena risolte.

### US-004 — Storico
- [ ] Dalla schermata principale si raggiunge lo storico senza interferire col pulsante
      START/STOP.
- [ ] I giri sono elencati dal più recente al più vecchio.
- [ ] Ogni riga mostra data/ora, distanza, durata e il sommario delle località.
- [ ] Il tap su una riga apre il dettaglio con tutte le statistiche salvate.
- [ ] Con storico vuoto compare un messaggio esplicativo, non una lista vuota muta.
- [ ] I giri salvati sopravvivono alla chiusura dell'app e al riavvio del dispositivo.

### US-005 — Cancellazione
- [ ] Un giro può essere cancellato dallo storico.
- [ ] La cancellazione chiede conferma.
- [ ] Dopo la conferma il giro sparisce dalla lista e dal file su disco.

### US-006 — Assenza di rete o di geocoder
- [ ] Senza rete il giro si salva con le sole statistiche e senza località.
- [ ] Nello storico un giro privo di località lo dichiara esplicitamente («località non
      disponibili») invece di mostrare una riga vuota.
- [ ] Il fallimento del geocoding non fa perdere il giro né manda l'app in crash.
- [ ] Se il `Geocoder` di sistema non risponde entro un tempo massimo si passa a
      Nominatim senza attesa percepibile dall'utente.
- [ ] Le richieste a Nominatim rispettano il limite di 1 al secondo e inviano uno
      User-Agent identificativo dell'app.

---

## 5. Rischi e dipendenze

| Rischio / dipendenza | Note |
|---|---|
| **Geocoder di sistema assente o muto** | Su immagini AOSP senza Google Play services `Geocoder.isPresent()` è falso o la chiamata non torna nulla: è esattamente lo scenario dell'emulatore usato da `demo-ride.sh`. Da qui la scelta del doppio canale; il ripiego va però verificato davvero, altrimenti la feature risulta non testabile in demo. |
| **Usage policy di Nominatim** | Servizio pubblico gratuito con limite di 1 richiesta/secondo, obbligo di User-Agent identificativo e divieto di uso massivo. Un giro campionato male genererebbe decine di richieste in pochi secondi e farebbe bloccare l'IP. Servono campionamento parsimonioso, throttling e cache. |
| **Qualità dei nomi restituiti** | Il campo «località» non è uniforme: in aperta campagna `locality` è spesso nullo e bisogna ripiegare su frazione, comune o provincia. Fra `Geocoder` e Nominatim i campi non coincidono, quindi serve una normalizzazione comune, altrimenti lo stesso giro produce sommari diversi a seconda della sorgente che ha risposto. |
| **Nuova dipendenza HTTP** | Il progetto oggi non fa chiamate HTTP proprie (osmdroid scarica le tile per conto suo). Aggiungere un client comporta valutare la dimensione dell'APK e il lint severo della release, che ha già bloccato una build per una dipendenza transitiva disallineata. |
| **Nessun formato di persistenza esistente** | Oggi si usa solo `SharedPreferences` con nome file `"bike"`, adatto a poche chiavi scalari e non a una lista di giri con liste annidate. Va scelto un formato (file JSON, o database) e la scelta è difficile da cambiare dopo che gli utenti hanno dati salvati. |
| **Privacy dei dati di posizione** | Lo storico è una cronologia degli spostamenti dell'utente. Va tenuto nello storage privato dell'app e va deciso esplicitamente se includerlo nel backup automatico di Android, che lo copierebbe fuori dal dispositivo. |
| **Corsa fra dialog e nuovo START** | `RideTracker.onStart()` azzera stato e tracciato: se l'utente riparte mentre il dialog è aperto, il giro da salvare svanisce sotto le mani della UI. Il giro concluso va congelato in una copia prima di mostrare la domanda. |
| **Morte del processo prima dello STOP** | La feature non copre il giro perso perché Android ha ucciso l'app: chi si aspetta «salva i miei giri» potrebbe darlo per scontato. Va comunicato o pianificato subito dopo. |
| **Consumo dati e batteria a fine giro** | Il geocoding parte proprio quando la batteria è al minimo dopo ore di GPS. Le richieste vanno contenute e non ripetute a ogni apertura dello storico. |
| **Crescita del file storico** | Poche centinaia di byte per giro rendono il problema remoto, ma un giro al giorno per anni va comunque letto in un colpo solo all'apertura dello storico: il formato scelto non deve richiedere di caricare tutto per mostrare le prime righe. |

---

## 6. Stima effort

Progetto a singolo sviluppatore, nessun backend coinvolto.

| Area | Attività | Stima (gg/uomo) |
|---|---|---|
| Core | Modello del giro salvato e persistenza su disco (scrittura, lettura, cancellazione) | 1,0 |
| Core | Campionamento del tracciato e deduplica delle località | 0,5 |
| Core | Reverse geocoding: `Geocoder` di sistema, ripiego Nominatim, normalizzazione dei nomi, throttling e cache | 1,5 |
| Core | Congelamento del giro concluso e sequenza STOP → domanda → salvataggio | 0,5 |
| FE | Dialog di conferma con statistiche del giro | 0,5 |
| FE | Schermata storico: lista, dettaglio, navigazione, stato vuoto | 1,25 |
| FE | Cancellazione con conferma e stati di errore/località mancanti | 0,5 |
| Test | Unit test su campionamento, deduplica, normalizzazione, serializzazione; verifica manuale su emulatore e telefono | 0,75 |
| Doc | Aggiornamento `CLAUDE.md` e note su usage policy Nominatim | 0,25 |
| **Totale** | | **6,75 gg** |

La stima non include le voci fuori scope (replay su mappa, export GPX, recupero di un
giro interrotto, statistiche aggregate).

---

## 7. Milestones

1. **M1 — Persistenza del giro**: modello del giro salvato, scrittura e lettura su
   storage privato, cancellazione. Nessuna località ancora: solo statistiche.
2. **M2 — Domanda a fine giro**: congelamento del giro allo STOP, dialog con le
   statistiche, soglia minima sotto la quale non si chiede, protezione dalla corsa col
   nuovo START.
3. **M3 — Storico**: schermata con lista dei giri, dettaglio, stato vuoto e cancellazione
   con conferma.
4. **M4 — Punti salienti**: campionamento del tracciato, reverse geocoding col `Geocoder`
   di sistema, deduplica delle località consecutive, aggiornamento del giro salvato
   appena i nomi sono risolti.
5. **M5 — Ripiego Nominatim**: client HTTP con User-Agent e throttling, normalizzazione
   comune dei nomi, cache per non ripetere le stesse richieste.
6. **M6 — Degrado e rifiniture**: comportamento senza rete, indicazione delle località non
   disponibili, verifica del lint di release.
7. **M7 — Verifica sul campo**: giro reale in zona con copertura discontinua, controllo
   della qualità dei nomi restituiti e del numero di richieste effettuate.

---

*Documento generato con la skill `claude-code-feature` — Fase 1.*
