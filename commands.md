# Script Documentation

This markdown file shows the documentation of the script to activate the
conda environment and run the script python file.

I will create a symlink in PATH to make it accesible everywhere in my computer
regardless of the location of the script. 

assuming i have a start_server.sh script and a kodlama-scriptleri directory on my user profile, This line creates a symlink for that:

first, i created directory to put my scripts:

mkdir -p ~/kodlama-scriptleri

then, i added a symlink to that file. with code beloww:
 
```
ln -s ~/Documents/GitHub/AttendenceCheck/start_server.sh ~/kodlama-scriptleri/start_server
chmod +x ~/Documents/GitHub/AttendenceCheck/start_server.sh
```

Then, i added this line of code to automatically export the symbolic link to each session of the terminal in ~/.zshrc file.

export PATH="$HOME/kodlama-scriptleri:$PATH"

this makes sure to add my script to path for each session in terminal automatically. 



