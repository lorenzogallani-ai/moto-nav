# MotoNav

Schermata Home su misura per lo smartwatch Ticwris Max 4G (Android 7.1), pensata per usarlo come navigatore in moto.

## Cosa fa
- **Orologio grande, data e batteria** (gialla sotto il 30%, rossa sotto il 15%).
- **Pulsantoni destinazione** (fino a 8): un tocco avvia subito la navigazione.
  - Tieni premuto un pulsante per cambiarne nome/indirizzo o eliminarlo; **+** ne aggiunge uno.
  - L'indirizzo viene convertito in coordinate appena lo salvi, così poi la navigazione parte anche senza internet.
- **MOTO**: luminosità al massimo, schermo acceso per 30 minuti, notifiche silenziate. Ripremi per tornare alle impostazioni di prima.
- **CERCA**: scrivi un posto e parte la navigazione (o lo salvi come pulsante).
- **APP**: elenco di tutte le app a caratteri grandi.
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
