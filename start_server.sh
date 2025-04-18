#!/bin/zsh

# activates environment

source /opt/miniconda3/etc/profile.d/conda.sh
conda activate AttendanceApp


# finds the location fo the app
cd ~/Documents/GitHub/AttendanceCheck

# Runs the server code
python3.13 server.py


