# MotoNav

Schermata Home su misura per lo smartwatch Ticwris Max 4G (Android 7.1), pensata per usarlo come navigatore in moto.

## Cosa fa
- **Due modalità, MOTO e MONOPATTINO** (interruttore sotto l'orologio). In moto il percorso è da moto e compare il logo R1200R; in monopattino il percorso è da bici (ciclabili) e compare il pulsante **CRUSCOTTO SEGWAY**.
- **Orologio grande, data e batteria** (gialla sotto il 30%, rossa sotto il 15%).
- **Pulsantoni destinazione** (fino a 8): un tocco avvia subito la navigazione.
  - Tieni premuto un pulsante per cambiarne nome/indirizzo o eliminarlo; **+** ne aggiunge uno.
  - L'indirizzo viene convertito in coordinate appena lo salvi, così poi la navigazione parte anche senza internet.
- **CRUSCOTTO SEGWAY** (Ninebot Max G30): velocità grande, batteria, autonomia, modalità ECO / D / S e fari, letti direttamente dal monopattino via Bluetooth. Resta collegato in sottofondo (notifica con velocità · batteria · autonomia).
  - Prima volta: accendi il monopattino, **chiudi l'app Segway sull'iPhone** (il monopattino accetta un solo collegamento alla volta), apri CRUSCOTTO e consenti la posizione (serve ad Android 7 per cercare il Bluetooth). Quando lo chiede, premi una volta il pulsante di accensione del monopattino per abbinarlo. Dopo si ricollega da solo.
  - ⚙ → "Dimentica il monopattino abbinato" per abbinarne un altro.
  - Il collegamento cifrato usa NinebotCrypto (scooterhacking, AGPL-3.0).
- **Schermo in navigazione**: con GUIDA attiva il **tasto superiore** spegne e riaccende lo schermo; OsmAnd, cruscotto e telecomando continuano a lavorare sotto, e alla riaccensione si torna subito alla mappa (senza schermata di blocco, se il blocco è "Scorrimento" o "Nessuno"). In OsmAnd: Impostazioni → profilo → Impostazioni generali → Controllo schermo → attiva "Accendi schermo" con **Tasto di accensione** e **Istruzioni di navigazione** (si accende da solo prima delle svolte).
- **GUIDA**: luminosità al massimo, schermo acceso per 30 minuti, notifiche silenziate. Ripremi per tornare alle impostazioni di prima.
- **CERCA**: scrivi un posto e parte la navigazione (o lo salvi come pulsante).
- **APP**: elenco di tutte le app a caratteri grandi.
- **♪ Telecomando iPhone**: play/pausa, volume (tieni premuto per salire/scendere veloce), brano precedente/successivo. L'orologio si presenta all'iPhone come un telecomando Bluetooth multimediale: nessuna app da installare sull'iPhone, i tasti comandano l'app che sta suonando (Spotify). Resta attivo in sottofondo con i comandi anche nella tendina delle notifiche, così funziona mentre navighi.
  - Prima volta: apri ♪ sull'orologio, poi sull'iPhone **Impostazioni → Bluetooth** e tocca il nome dell'orologio sotto "Altri dispositivi"; conferma l'abbinamento su entrambi. Dopo si ricollega da solo.
- **⚙**: scelta del navigatore (OsmAnd, Organic Maps, Google Maps), profilo Moto/Auto, download di OsmAnd, impostare MotoNav come Home.

La navigazione vera e propria (mappe, percorso, voce) la fa **OsmAnd**: MotoNav gli passa la destinazione col profilo Moto.

## Installazione sull'orologio
1. Ogni modifica caricata su `main` viene compilata automaticamente (scheda **Actions**).
2. L'APK pronto è nella pagina **Releases**: file `moto-nav.apk`.
3. Dal browser dell'orologio scarica l'APK e aprilo (consenti "origini sconosciute" se richiesto).
4. Le nuove versioni si installano sopra la precedente senza perdere i pulsanti.

## Primo avvio
1. ⚙ → **Scarica OsmAnd** e installalo. In OsmAnd scarica la mappa dell'Italia (o della Lombardia) e attiva il profilo **Moto** (Impostazioni → Profili).
2. Tieni premuto **Casa** e **Ufficio** per inserire gli indirizzi.
3. Premi **MOTO** la prima volta: chiederà il permesso di modificare le impostazioni (per la luminosità) e l'accesso "Non disturbare".
4. ⚙ → **Usa MotoNav come schermata Home** e scegli MotoNav.

Se OsmAnd segnala un problema col profilo, in ⚙ cambia "Profilo percorso" da Moto ad Auto.
