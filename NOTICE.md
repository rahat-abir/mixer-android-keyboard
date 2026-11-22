# Third-party data and code

| What | Source | Licence | Where |
|---|---|---|---|
| Avro Phonetic rules, regex rules, Bangla dictionary, suffix list, autocorrect list | ibus-avro (github.com/sarim/ibus-avro), originally jsAvroPhonetic © OmicronLab | MPL-2.0 | `app/src/main/assets/avro/*`, ported logic in `bangla/AvroPhonetic.kt`, `bangla/BanglaSuggester.kt` |
| English word list with frequencies and bigrams | AOSP / OpenBoard `en_US` wordlist via codeberg.org/Helium314/aosp-dictionaries | Apache-2.0 | `app/src/main/assets/en_*.txt` |
| Bangla word frequencies (Bangla glide ranking) | wordfreq `large_bn` by Robyn Speer et al. (github.com/rspeer/wordfreq) | CC BY-SA 4.0 | `app/src/main/assets/bn_glide.txt` (derived: words, frequency bins, generated swipe spellings) |
| Emoji list | Unicode `emoji-test.txt` | Unicode License | `app/src/main/assets/emoji.txt` |
| Glide typing approach | SHARK² shape matching, as used by FlorisBoard's statistical glide classifier (reimplemented, no code copied) | Apache-2.0 (FlorisBoard) | `glide/GlideClassifier.kt` |

Regenerate the assets with:

```
node scripts/build_assets.js .tools/downloads/data app/src/main/assets
```
