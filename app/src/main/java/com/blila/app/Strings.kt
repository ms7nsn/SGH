package com.blila.app

/** English -> French dictionary. "{}" marks a part that changes (numbers, names) and is translated recursively. */
private class Tpl(val prefix: String, val suffix: String, val rx: Regex, val fr: String)

private object Dict {
    val exact = HashMap<String, String>()
    val tpls = ArrayList<Tpl>()

    init {
        val sorted = PAIRS.sortedWith(
            compareBy<Pair<String, String>> { it.first.split("{}").size }.thenByDescending { it.first.length }
        )
        for ((en, fr) in sorted) {
            if (!en.contains("{}")) {
                exact[en] = fr
                continue
            }
            val parts = en.split("{}")
            val rx = Regex(parts.joinToString("(.*?)") { Regex.escape(it) })
            var out = fr
            if (!out.contains("{1}")) {
                var i = 1
                while (out.contains("{}")) {
                    out = out.replaceFirst("{}", "{" + i + "}")
                    i++
                }
            }
            tpls.add(Tpl(parts.first(), parts.last(), rx, out))
        }
    }
}

fun tr(s: String): String {
    if (!Lang.fr || s.length < 2) return s
    Dict.exact[s]?.let { return it }
    for (tp in Dict.tpls) {
        if (!s.startsWith(tp.prefix) || !s.endsWith(tp.suffix)) continue
        val m = tp.rx.matchEntire(s) ?: continue
        var out = tp.fr
        for (i in 1 until m.groupValues.size) out = out.replace("{" + i + "}", tr(m.groupValues[i]))
        return out
    }
    return s
}

private val PAIRS: List<Pair<String, String>> = listOf(
    "Other" to "Autre",
    "Other ({})" to "Autre ({})",
    "Location permission is needed for GPS data." to "L'autorisation de localisation est nécessaire pour les données GPS.",
    "Grant location permission" to "Accorder l'autorisation de localisation",
    "Fetching online info…" to "Récupération des infos en ligne…",
    "Online info needs a position fix" to "Les infos en ligne nécessitent une position GPS",
    "Online info · {}" to "Infos en ligne · {}",
    "Online info" to "Infos en ligne",
    "Refresh" to "Actualiser",
    "GPS is off" to "GPS désactivé",
    "3D fix" to "Fix 3D",
    "2D fix" to "Fix 2D",
    "No fix" to "Pas de fix",
    "GNSS status" to "État GNSS",
    "Satellites in view / used" to "Satellites en vue / utilisés",
    "Per system (used / in view)" to "Par système (utilisés / en vue)",
    "Time to first fix" to "Temps avant le premier fix",
    "Horizontal accuracy" to "Précision horizontale",
    "Vertical accuracy" to "Précision verticale",
    "Speed accuracy" to "Précision de la vitesse",
    "Heading accuracy" to "Précision du cap",
    "Fix age" to "Âge du fix",
    "Provider" to "Fournisseur",
    "Waiting for a fix…" to "En attente d'un fix…",
    "Waiting for a location fix… go outside or near a window." to "En attente d'une position… sors ou approche-toi d'une fenêtre.",
    "Accuracy" to "Précision",
    "Copy" to "Copier",
    "Open in maps" to "Ouvrir dans Cartes",
    "Online" to "En ligne",
    "Address" to "Adresse",
    "Place" to "Lieu",
    "Time zone" to "Fuseau horaire",
    "Speed & heading" to "Vitesse et cap",
    "Waiting for a location fix…" to "En attente d'une position…",
    "Speed" to "Vitesse",
    "Heading (GPS)" to "Cap (GPS)",
    "– (move to get a heading)" to "– (déplace-toi pour obtenir un cap)",
    "Wind here (online)" to "Vent ici (en ligne)",
    "Wind" to "Vent",
    "{} km/h from {}" to "{} km/h venant de {}",
    "Altitude & pressure" to "Altitude et pression",
    "Ellipsoid altitude (WGS84)" to "Altitude ellipsoïdale (WGS84)",
    "No barometer on this device." to "Pas de baromètre sur cet appareil.",
    "Barometer: waiting for data…" to "Baromètre : en attente de données…",
    "Altitude (barometric)" to "Altitude (barométrique)",
    "Pressure" to "Pression",
    "Sea-level reference" to "Référence niveau de la mer",
    "Terrain elevation" to "Altitude du terrain",
    "Sea-level pressure (weather)" to "Pression niveau mer (météo)",
    "Calibrate barometer" to "Calibrer le baromètre",
    "To GPS" to "Sur GPS",
    "To terrain" to "Sur terrain",
    "To weather" to "Sur météo",
    "Reset" to "Réinit.",
    "No sensor" to "Aucun capteur",
    "Compass" to "Boussole",
    "No compass sensor on this device." to "Pas de boussole sur cet appareil.",
    "Magnetic heading" to "Cap magnétique",
    "True heading" to "Cap vrai",
    "Declination" to "Déclinaison",
    "High (3/3)" to "Haute (3/3)",
    "Medium (2/3)" to "Moyenne (2/3)",
    "Low (1/3)" to "Faible (1/3)",
    "Unreliable (0/3)" to "Peu fiable (0/3)",
    "Wave the phone in a figure-8 motion to calibrate the compass." to "Fais des 8 avec le téléphone pour calibrer la boussole.",
    "Magnetic field" to "Champ magnétique",
    "Satellite finder" to "Recherche de satellites",
    "Turns with the compass: a satellite at the top is straight ahead of the phone." to "Tourne avec la boussole : un satellite en haut est droit devant le téléphone.",
    "No compass sensor: north is at the top." to "Pas de boussole : le nord est en haut.",
    "Heading {}° {} · filled = used in the fix · outline = in view · colour = signal" to "Cap {}° {} · plein = utilisé · contour = en vue · couleur = signal",
    "{} in view · {} used" to "{} en vue · {} utilisés",
    "No satellites yet." to "Aucun satellite pour l'instant.",
    "Signal strength (C/N0 dB-Hz)" to "Force du signal (C/N0 dB-Hz)",
    "Cyan label = used in the fix. Above 40 is very good. Tap a satellite for details." to "Étiquette colorée = utilisé dans le fix. Au-dessus de 40, c'est très bon. Touche un satellite pour les détails.",
    "System" to "Système",
    "Elev" to "Élév.",
    "yes" to "oui",
    "no" to "non",
    "yes / yes" to "oui / oui",
    "yes / no" to "oui / non",
    "no / yes" to "non / oui",
    "no / no" to "non / non",
    "Carrier frequency" to "Fréquence porteuse",
    "Elevation / azimuth" to "Élévation / azimut",
    "Used in fix" to "Utilisé dans le fix",
    "Ephemeris / almanac" to "Éphémérides / almanach",
    "Tap to load" to "Touche pour charger",
    "Loaded" to "Chargé",
    "Place & weather (online)" to "Lieu et météo (en ligne)",
    "Temperature" to "Température",
    "Feels like" to "Ressenti",
    "Humidity" to "Humidité",
    "Pressure (sea level)" to "Pression (niveau mer)",
    "Cloud cover" to "Couverture nuageuse",
    "Sunrise" to "Lever du soleil",
    "Sunset" to "Coucher du soleil",
    "Your coordinates are sent to OpenStreetMap Nominatim and Open-Meteo to get this data." to "Tes coordonnées sont envoyées à OpenStreetMap Nominatim et Open-Meteo pour obtenir ces données.",
    "Bandwidth" to "Bande passante",
    "Technology" to "Technologie",
    "Cell ID" to "ID de cellule",
    "Channel (ARFCN)" to "Canal (ARFCN)",
    "Band" to "Bande",
    "{} dBm (level {}/4)" to "{} dBm (niveau {}/4)",
    "Timing advance" to "Avance temporelle (TA)",
    "≈ {} m from the tower" to "≈ {} m de l'antenne",
    "Age of reading" to "Âge de la mesure",
    "Location (online)" to "Localisation (en ligne)",
    "This cell has no complete identity, so it cannot be looked up." to "Cette cellule n'a pas d'identité complète, elle ne peut pas être recherchée.",
    "Add an OpenCelliD API key (Tower location card) to locate this tower." to "Ajoute une clé API OpenCelliD (carte Localisation des antennes) pour localiser cette antenne.",
    "Tower position" to "Position de l'antenne",
    "Coverage radius" to "Rayon de couverture",
    "Database samples" to "Échantillons en base",
    "Distance from you" to "Distance depuis toi",
    "Looking up…" to "Recherche…",
    "Not in the OpenCelliD database." to "Absente de la base OpenCelliD.",
    "Waiting for lookup…" to "En attente de la recherche…",
    "error: {}" to "erreur : {}",
    "Nearby cells" to "Cellules proches",
    "{} visible · {} used" to "{} visibles · {} utilisées",
    "No cells reported yet." to "Aucune cellule signalée pour l'instant.",
    "Tap a cell for details." to "Touche une cellule pour les détails.",
    "{} · used" to "{} · utilisée",
    "ch {}" to "can. {}",
    "API key needed" to "Clé API nécessaire",
    "{} located" to "{} localisée(s)",
    "Tower location (online)" to "Localisation des antennes (en ligne)",
    "Android does not give the position of a tower, only its identity. SGH looks each cell up in OpenCelliD, a free crowd-sourced database. Create a free account on opencellid.org, copy your API access token and paste it here. Coverage varies by region and operator." to "Android ne donne pas la position d'une antenne, seulement son identité. SGH cherche chaque cellule dans OpenCelliD, une base gratuite alimentée par la communauté. Crée un compte gratuit sur opencellid.org, copie ton jeton d'accès API et colle-le ici. La couverture varie selon la région et l'opérateur.",
    "OpenCelliD API key" to "Clé API OpenCelliD",
    "Save and locate" to "Enregistrer et localiser",
    "Remove key" to "Supprimer la clé",
    "Cell identities and your key are sent to opencellid.org for each lookup." to "Les identités de cellules et ta clé sont envoyées à opencellid.org à chaque recherche.",
    "Location permission is needed to read cell towers." to "L'autorisation de localisation est nécessaire pour lire les antennes.",
    "Permission denied for cell info." to "Autorisation refusée pour les infos cellulaires.",
    "Cell info error" to "Erreur d'infos cellulaires",
    "No cell info (no SIM, airplane mode or location off)." to "Pas d'infos cellulaires (pas de SIM, mode avion ou localisation désactivée).",
    "No serving cell" to "Aucune cellule utilisée",
    "Serving cell (used)" to "Cellule utilisée (serveuse)",
    "Operator" to "Opérateur",
    "Network" to "Réseau",
    "Roaming" to "Itinérance",
    "No serving cell reported." to "Aucune cellule serveuse signalée.",
    "This sensor was not found on this device." to "Ce capteur est introuvable sur cet appareil.",
    "The sensor could not be started." to "Le capteur n'a pas pu démarrer.",
    "Body sensors permission is required." to "L'autorisation Capteurs corporels est nécessaire.",
    "Sensor light" to "Lumière du capteur",
    "Lights the emitters of the heart-rate sensor (next to the camera flash). Turns off when you leave the app." to "Allume les émetteurs du capteur de fréquence cardiaque (à côté du flash). S'éteint quand tu quittes l'app.",
    "Red" to "Rouge",
    "Infrared (invisible)" to "Infrarouge (invisible)",
    "Android has no direct way to drive these LEDs, so this is best effort. The light may look dim or pulsed." to "Android ne permet pas de piloter ces LED directement : c'est donc du mieux possible. La lumière peut paraître faible ou clignotante.",
    "Detected heart-rate sensors" to "Capteurs de fréquence cardiaque détectés",
    "Accelerometer" to "Accéléromètre",
    "Magnetometer" to "Magnétomètre",
    "Resume" to "Reprendre",
    "Not available on this device." to "Indisponible sur cet appareil.",
    "Paused" to "En pause",
    "G-Force" to "Force G",
    "Accelerometer not available on this device." to "Accéléromètre indisponible sur cet appareil.",
    "Overview" to "Aperçu",
    "Raw data" to "Données brutes",
    "Peak" to "Pic",
    "Include gravity" to "Inclure la gravité",
    "Reset peak" to "Réinit. le pic",
    "At rest the value is about 1 g." to "Au repos, la valeur est d'environ 1 g.",
    "Gravity removed: at rest the value is about 0 g." to "Gravité retirée : au repos, la valeur est d'environ 0 g.",
    "Latest sample" to "Dernier échantillon",
    "Event timestamp" to "Horodatage de l'événement",
    "Sample rate" to "Fréquence d'échantillonnage",
    "Last 12 samples" to "12 derniers échantillons",
    "Sensor" to "Capteur",
    "Name" to "Nom",
    "Vendor" to "Fabricant",
    "Max range" to "Plage max",
    "Resolution" to "Résolution",
    "Fastest rate" to "Fréquence max",
    "Power" to "Consommation",
    "No heart-rate sensor found on this device." to "Aucun capteur de fréquence cardiaque trouvé sur cet appareil.",
    "No heart-rate sensor was found on this device." to "Aucun capteur de fréquence cardiaque trouvé sur cet appareil.",
    "SpO₂ needs the Samsung raw red/IR sensors, which were not found on this device." to "La SpO₂ nécessite les capteurs bruts rouge/IR de Samsung, introuvables sur cet appareil.",
    "Error when accessing the heart-rate sensor. Check the Body sensors permission." to "Erreur d'accès au capteur de fréquence cardiaque. Vérifie l'autorisation Capteurs corporels.",
    "Signal too weak or unstable. Press gently but firmly, keep still, and try again." to "Signal trop faible ou instable. Appuie doucement mais fermement, reste immobile et réessaie.",
    "Done. Estimate only, not a medical measurement." to "Terminé. Simple estimation, pas une mesure médicale.",
    "Heart rate" to "Fréquence cardiaque",
    "Blood oxygen (SpO₂)" to "Oxygène sanguin (SpO₂)",
    "Estimates from the phone's built-in sensor. Not a medical device and not for diagnosis." to "Estimations du capteur intégré du téléphone. Pas un dispositif médical, pas pour le diagnostic.",
    "Live pulse signal" to "Signal de pouls en direct",
    "IR {}  ·  Red {}  ·  Finger: {}" to "IR {}  ·  Rouge {}  ·  Doigt : {}",
    "{}  ·  PI {}%" to "{}  ·  IP {}%",
    "Place your fingertip on the sensor next to the camera flash and hold still…" to "Place le bout du doigt sur le capteur à côté du flash et reste immobile…",
    "Measuring… keep your finger still" to "Mesure en cours… garde le doigt immobile",
    "Grant sensor permission" to "Accorder l'autorisation du capteur",
    "Measure" to "Mesurer",
    "Cancel" to "Annuler",
    "Swap IR / Red channels" to "Inverser les canaux IR / Rouge",
    "SpO₂ offset: {} %" to "Décalage SpO₂ : {} %",
    "Finger threshold: {}" to "Seuil du doigt : {}",
    "SpO₂ formula" to "Formule SpO₂",
    "Vitals" to "Vitaux",
    "Cells" to "Cellules",
    "Motion" to "Mouvement"
)
