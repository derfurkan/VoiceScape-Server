# VoiceScape-Server
### The Server software for [VoiceScape](https://github.com/derfurkan/VoiceScape "VoiceScape")

## How to run
#### To run the Server software you need to have [Java 17](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html "Java 17") installed on your system.<br/>
Get the latest release _(or build your own)_ and run it with the following command in your command line<br/>
`java -jar VoiceScape-Server.jar`

After executing should see a message that says `-- Server started --`<br/>
At this point you can connect to the with the following ip's<br/>
`VoiceServer` - `127.0.0.1:24444`<br/>
`MessageServer` - `127.0.0.1:25555`

[VoiceScape](https://github.com/derfurkan/VoiceScape "VoiceScape") needs to connect to BOTH of them so make sure that your ports are open if you want that other players also can connect to your server.

## Code Variables
At the code in the `Core`.java class you can see a few variables at the top.<br/>
Here i will describe them in detail<br/><br/>
* `KILL_SOCKET_IF_INVALID_MESSAGE` _(TRUE by default)_ - If set to true every client that sends an unrecognized message will be disconnected from the server. This can happen due an update or an modification of the client.<br/><br/>
* `MIN_LOGIN_TIMEOUT_MS` _(5.000 by default)_ - The time in milliseconds the client must wait until it can reconnect.<br/><br/>
* `LOGIN_SPAM_BLACKLIST_BAN_MS` _(60.000 by default)_ - The time in milliseconds the client will be banned from the server if the client reconnects all the time.<br/><br/>
* `MIN_MESSAGE_TIMEOUT_MS` _(800 by default)_ - The time in milliseconds the client must wait until send a new message to the server. If it sends messages to fast the client will be kicked and temporarily banned.<br/><br/>
* `MESSAGE_SPAM_BLACKLIST_BAN_MS` _(20.000 by default)_ - The time in milliseconds the client will be banned from the server if the client sends message to fast.<br/><br/>
* `MESSAGE_THREAD_WAIT_TIME_MS` _(20.000 by default)_ - The time in milliseconds the client has to connect to the message server. Since the server opens two serversockets `Voice` and `Message` it will ensure that the client is connected to both of them. So if the client only connects to the `Voice` server and not to the `Message` server the server will disconnect the client after the value of milliseconds.<br/><br/>
* `FLAG_REMOVE_TIMEOUT_MS` _(5.000 by default)_ - Every client has flags and if the client reaches 3 or more it will be disconnected and blacklisted. Flags can be gained with message spam up to three times. This value represents the time in milliseconds which must be waited before a flag is being removed.<br/><br/>
* `REGISTRATION_TIMEOUT_MS` _(5.000 by default)_ - The time in milliseconds the client has to register itself with its local username. If not registered in time the client will be disconnected.<br/><br/><br/>
* `VOICE_SERVER_PORT` _(24444 by default)_ - The port on which the voice server should be opened on.<br/><br/>
* `MESSAGE_SERVER_PORT` _(25555 by default)_ - The port on which the message server should be opened on.<br/><br/>






