# SSH User Password Change 
## Overview
The tool is designed to automate ssh user password change.
If you have number of hosts/users that need password changing it will surely save your time. 
There can be different reasons to change passwords: passwords have expired (when live period is set by security policy),
passwords were exposed earlier and need to be updated, etc.  
## Usage
Before running make sure Java Runtime Environment (JRE) or JDK is installed on your machine.

To change password run one of the below commands in console/bash:
```
./start.sh users_to_update.txt
```
``` 
./start.sh testuser qwerty qwerty123 10.18.40.30,10.18.40.31
```
```
./start.sh testuser qwerty qwerty123     
```
**Note**: use _start.cmd_ on Windows and _./start_gitbash.sh_ in git bash. 

It will connect to each host provided and change user password.
The default hosts are also can be set in settings.properties file.
## Build
```
gradlew clean build
```
After successful build you can find release media in 'package' folder.  
