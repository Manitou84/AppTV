# VOD TV — client Android TV (Kotlin · Compose for TV · Media3)

App nativa per Android TV / Google TV, controllabile interamente dal telecomando, che riproduce flussi HLS (.m3u8) dal tuo backend privato e i contenuti MotoGP / Formula 1 resi disponibili dalla RSI.

## Aprire e avviare

1. Apri la cartella `VodTv` in **Android Studio** (Ladybug o successivo, JDK 17).
2. Lascia fare il *Gradle Sync*: scarica dipendenze e SDK 35.
3. Avvia su un emulatore **Android TV** (API 26+) o su un dispositivo collegato via ADB
   (`adb connect <ip-della-tv>`, con il debug di rete attivo nelle Opzioni sviluppatore).
4. Al primo avvio vai in **Impostazioni**: inserisci *Base URL*, *Dominio sorgente*, *Dominio iframe* e la *Chiave API TMDB*, poi *Verifica connessione*.

> Il codice non è stato compilato nell'ambiente in cui è stato scritto (manca l'SDK Android).
> Se il sync segnala versioni non allineate (AGP / Kotlin / KSP), aggiornale in `build.gradle.kts` della radice
> e nel blocco versioni di `app/build.gradle.kts`.

## Struttura (MVVM)

```
app/src/main/java/com/vodtv/client/
├── VodTvApp.kt                 Application + AppContainer (DI manuale)
├── MainActivity.kt
├── data/
│   ├── model/                  Modelli di dominio, chiavi di avanzamento, eccezioni
│   ├── remote/                 Ktor: HttpClientFactory, VodApiService, VodJsonParser, UrlUtils
│   ├── repository/             VodRepository (rete) · LibraryRepository (preferiti, cronologia)
│   ├── local/                  Room: entità, DAO, database
│   ├── rsi/                    Integration layer SRG SSR: dirette, archivio, risoluzione flussi
│   ├── settings/               DataStore: Base URL e dominio iframe
│   └── playback/               PlaybackSession condivisa tra schermate e player
└── ui/
    ├── navigation/             NavHost + barra in alto (Home · Film · Serie TV · Preferiti · Motori · Impostazioni)
    ├── components/             TopNavBar, PosterCard, WideCard, TvTextField, pulsanti
    ├── home/                   "Continua a guardare"
    ├── catalog/                Film e Serie TV: ricerca + classificazione (ViewModel principale)
    ├── favorites/              Preferiti divisi in Serie TV e Film
    ├── detail/                 Dettaglio, stagioni, episodi visti, Riprendi, preferiti
    ├── motors/                 MotoGP e Formula 1 dalla RSI
    ├── player/                 ExoPlayer + PlayerView, tasti telecomando, episodio successivo
    └── settings/
```

## Comportamenti principali

| Funzione | Come funziona |
|---|---|
| Focus D-pad | Card, pulsanti, chip e righe sono componenti `tv-material` già focalizzabili (niente `Modifier.focusable()` aggiuntivo, che creerebbe doppie fermate). Focus = bordo ambra + ingrandimento. |
| Campi di testo | Spostarsi non apre la tastiera; **OK** la apre; **▲/▼** escono dal campo. |
| Ricerca | Parte da sola 600 ms dopo l'ultimo carattere (min 2), oppure con *Cerca*. |
| Film / Serie TV | Usa il campo `type` della ricerca se presente; altrimenti apre il dettaglio dei risultati in background (max 3 alla volta) e salva il tipo in locale. |
| Continua a guardare | Posizione salvata ogni 10 s e all'uscita dal player. Visto = oltre il 90%. |
| Riprendi | Film: dal punto salvato. Serie: episodio a metà, altrimenti il primo non visto dopo l'ultimo visto. |
| Visto / non visto | Pressione lunga su OK (episodio, oppure *Riproduci* per i film). |
| Episodio successivo | A fine episodio: riquadro con conto alla rovescia di 10 s; *Annulla* chiude. |
| Player | ◀ ▶ ±10 s · OK pausa · tasti media · Indietro chiude prima i controlli, poi il player. |
| Sorgenti multiple | Se `get_links` restituisce più flussi compare la scelta (saltata con un solo flusso). |
| TMDB | Con `tmdb_id` e chiave impostata, il Dettaglio usa titolo, trama, locandina, sfondo, generi, voto e durate in italiano da TMDB; episodi con nome, trama e durata. Senza chiave restano i dati del backend. |
| Dominio sorgente | Inviato come parametro `domain` a `/api/search` e `/api/load` (nome modificabile in `VodApiService.SOURCE_DOMAIN_PARAM`). |
| Motori (RSI) | Dirette, palinsesto e archivio filtrati per sport. L'app chiede alla RSI il flusso e lo riproduce solo se la RSI lo concede; altrimenti mostra il motivo (es. "Solo in Svizzera"). |

## Contratto del backend VOD

- `GET /api/search?q=` → lista di `{name, slug, image}` (accetta anche `type` opzionale: `movie` / `tv`)
- `GET /api/load?slug=` → `{title|name, plot, image, tmdb_id, seasons…}`; stagioni in array, mappa o lista piatta di episodi
- `GET /api/get_links?iframe_domain=&tmdb_id=&season=&episode=` → dizionario annidato di URL; vengono raccolti tutti gli URL, preferendo i `.m3u8`
- Per i film `season`/`episode` vengono **omessi**. Se il backend vuole la stringa `"null"`, usa `sendNullLiteral = true` in `VodApiService.getLinks`.

## Da verificare / prossimi passi

- **Percorsi RSI per liste e ricerca**: `RsiRepository` prova più percorsi candidati dell'integration layer SRG SSR. Il recupero del flusso (`mediaComposition`, token Akamai, `blockReason`) segue il formato pubblico usato dal player web; i percorsi delle liste vanno confermati con una prova reale.
- **Orari TV8**: non implementati, serve scegliere una fonte dati affidabile per il palinsesto.
- **Banner e icona**: sono vettoriali provvisori in `res/drawable`.
