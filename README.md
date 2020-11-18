# SSH Password Change 
## Overview
SSH Password Change is a tool that automates user's password change connecting via ssh.
In case you have several hosts/users that need to be updated with a new password the tool will save your time.
## Usage
Before running make sure Java Runtime Environment (JRE) or JDK is installed on your machine.

To change password run below command in console/bash:
``` 
Windows: start.cmd testuser qwerty qwerty123 10.18.40.30,10.18.40.31
Linux: ./start.sh testuser qwerty qwerty123 10.18.40.30,10.18.40.31     
```
It will connect to each host provided and change user's password.
The hosts is also can be set in settings.properties file.
## Build
```
gradlew clean build
```
After successful build you will find release media in 'package' folder.  
