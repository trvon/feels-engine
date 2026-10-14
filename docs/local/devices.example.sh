# Copy to devices.sh (ignored by git) and fill in. `adb devices -l` lists serials;
# `adb shell dumpsys display | grep uniqueId` lists physical display ids.
export PHONE=""                 # serial of a phone or tablet
export FOLD=""                  # serial of a foldable, if any
export FOLD_COVER_DISPLAY=""    # physical display id of the cover screen
export FOLD_INNER_DISPLAY=""    # physical display id of the inner screen
