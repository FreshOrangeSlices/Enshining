#!/bin/sh
# The experiments file: everything we're trying out, on the blank girl rig, kept out of the real characters.
# usage: tools/make_experiments.sh base_rig.bbmodel out.bbmodel
set -e
cd "$(dirname "$0")"
python3 make_hair_practice.py "$1" /tmp/experiments_hair.bbmodel
python3 make_eye_practice.py /tmp/experiments_hair.bbmodel "$2"
