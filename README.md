# click2whoosh

Utiliser une manette Zwift Click (V1 ou V2) dans MyWhoosh, gratuitement, depuis un téléphone Android.

MyWhoosh (5.7 et plus) sait piloter des manettes **OpenBikeControl** (OBC) par le réseau : il les trouve
par mDNS puis s'y connecte en TCP. L'app joue ce rôle : elle se connecte au Click en Bluetooth et
retransmet les appuis à MyWhoosh. Le home trainer reste connecté directement à MyWhoosh, qui gère
les vitesses virtuelles ; le pont ne fait que transmettre « vitesse + » et « vitesse − ».

> **État : pas encore testé avec un vrai Click ni un vrai MyWhoosh.** Le protocole OBC et le
> décodage Zwift sont couverts par des tests unitaires, le reste est à valider en conditions réelles.

## Installer l'APK

1. Sur le téléphone, ouvrir l'onglet **Releases** du dépôt et télécharger `click2whoosh.apk` dans
   la release « derniere-version » (reconstruite à chaque push sur la branche par défaut).
   Autre option : onglet **Actions**, dernier run, artefact `click2whoosh-apk` (un zip à extraire).
2. Ouvrir le fichier et autoriser l'installation d'applications inconnues si Android le demande.

Toutes les versions sont signées avec la même clé de debug (`android/app/debug.keystore`, publique),
donc une nouvelle version s'installe par-dessus l'ancienne.

## Utilisation

Le téléphone doit être en **Wi-Fi**, sur le même réseau que l'appareil qui fait tourner MyWhoosh
(ou MyWhoosh sur le même téléphone).

**Premier test, sans le Click** (vérifie que MyWhoosh accepte la manette) :

1. Ouvrir click2whoosh, **Démarrer**, accepter les permissions (Bluetooth, notifications).
2. Dans MyWhoosh : écran **Connexion**, icône **OpenBikeControl**, répondre **Oui** à « click2whoosh ».
   La ligne « App(s) » de click2whoosh doit afficher MyWhoosh.
3. En roulant, appuyer sur **Vitesse +** / **Vitesse −** dans click2whoosh : la vitesse doit changer.

**Avec le Click** :

1. Fermer Zwift et BikeControl : le Click ne se connecte qu'à une app à la fois.
2. Appuyer sur un bouton du Click pour le réveiller. click2whoosh le trouve et s'y connecte
   (« prête, boutons reçus » après le premier appui).
3. Si MyWhoosh tourne sur le même téléphone, laisser click2whoosh démarré en arrière-plan :
   la notification « click2whoosh actif » montre qu'il tourne toujours.

L'interrupteur **Debug** affiche les octets reçus et le nom des boutons dans le journal ;
**Copier le journal** permet de le partager en cas de souci.

## Click V2

- Les deux moitiés sont deux appareils Bluetooth. La gauche relaie aussi les boutons de la droite ;
  l'app se connecte à toutes les manettes Zwift trouvées et fusionne leurs boutons.
- Zwift veut que le Click V2 soit activé chaque jour dans son app. **Sans activation, il marche quand même** :
  l'app voit qu'il refuse le `RideOn`, passe en « mode sans Zwift » (plus de `RideOn`, comme BikeControl
  sans déverrouillage) et le reprend tout seul chaque fois qu'il décroche, environ toutes les minutes
  d'après BikeControl. Un appui pendant la reconnexion (une seconde ou deux) peut être perdu.
- Pour éviter ces coupures, l'activation officielle : app Zwift (pas Companion), compte gratuit sans
  abonnement, connecter le trainer puis le Click, attendre 10 à 30 s, forcer l'arrêt de Zwift, puis
  **Rechercher les manettes**. Elle dure environ une journée.
- Si une seule moitié répond, activer **Côté droit seul** : **+** passe la vitesse +, **B** la vitesse −.

## Correspondance des boutons

| Manette | OpenBikeControl |
|---|---|
| + (Click V1, Click V2 droit, shift droit du Ride) | 0x01 vitesse + |
| − (Click V1, Click V2 gauche, shift gauche du Ride) | 0x02 vitesse − |
| B | 0x15 retour (0x02 vitesse − en mode « côté droit seul ») |
| A / Y / Z | 0x14 valider / 0x16 menu / 0x20 emote |
| haut / bas | 0x10 / 0x11 |
| gauche / droite | 0x18 / 0x19 tourner (0x12 / 0x13 si l'app ne gère que la navigation) |

Seules les transitions sont envoyées (appui puis relâchement), jamais les paquets répétés du Click.

## En cas de souci

- **MyWhoosh ne voit pas click2whoosh** : même réseau Wi-Fi des deux côtés ; certains routeurs
  « invités » bloquent le mDNS. Le statut affiche le nom annoncé, le port et l'IP du téléphone.
- **Le Click n'est pas trouvé** : Bluetooth activé, Zwift/BikeControl fermés, appuyer sur un bouton
  du Click pendant la recherche. Sur Android 11 et moins, la localisation doit être activée.
- **Le pont s'arrête en pleine sortie** : dans les réglages Android de click2whoosh, mettre la
  batterie sur « Non restreinte » (le nom varie selon les marques).

## Développement

```
android/                Projet Gradle (Kotlin, aucune dépendance, minSdk 26, targetSdk 35)
  app/src/main/java/…/
    core/               Protocoles en Kotlin pur, testés sur JVM
      Obc.kt            Messages OpenBikeControl (boutons, statut, App Info, haptique)
      Zwift.kt          Décodage des manettes Zwift (0x37 Click, 0x23 Click V2 / Ride)
      ButtonRouter.kt   Fusion des manettes, correspondance, envoi sur changement seulement
      ObcTcpServer.kt   Serveur TCP OBC (port 21587, statut toutes les 30 s)
    ZwiftBle.kt         Scan BLE, connexion GATT, file d'opérations, reconnexion auto
    MdnsAdvertiser.kt   Annonce `_openbikecontrol._tcp` via NsdManager
    BridgeService.kt    Service de premier plan (connectedDevice) qui fait tourner le pont
    MainActivity.kt     Écran construit en code
.github/workflows/      Build de l'APK par GitHub Actions
```

Construire en local (JDK 17 et SDK Android requis) :

```bash
cd android && ./gradlew testDebugUnitTest assembleDebug
```

Pas besoin d'Android Studio : chaque push lance le workflow qui teste et produit l'APK.

## Références

- Protocole OpenBikeControl (MIT) : <https://github.com/OpenBikeControl/openbikecontrol-protocol>
- Constantes Zwift recoupées avec la version GPL-3.0 de BikeControl (tag `gpl3`) ; aucun code repris.
- Autre implémentation (Swift, Click V2 vers MyWhoosh) : <https://github.com/grachpower/free-bike-control>

Projet non affilié à Zwift, MyWhoosh ou BikeControl.
