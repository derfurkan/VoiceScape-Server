# VoiceScape-Server
### The Server software for [VoiceScape](https://github.com/derfurkan/VoiceScape "VoiceScape")
Note that this is my first project using the socket functions in java.

## How to run
#### To run the Server software you need to have [Java 17](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html "Java 17") installed on your system.<br/>
Get the latest release _(or build your own)_ and run it with the following command in your command line<br/>
`java -jar VoiceScape-Server.jar`

After executing you should see a message that says `-- Server started --`<br/>
At this point you can connect to the server with the ip
`127.0.0.1` on your local computer. Where the port `24444` is binded to the Voice and `23333` to the Message server.<br/><br/>
If you want that other players can connect to your server you have to forward port `24444` and `23333`. <br/>
There are multiple guides on the internet on how to do it with your specific internet provider.

## Code Variables
At the code in the `Core`.java class you can see a few variables at the top.<br/>
Here i will describe them in detail<br/><br/>
* `KILL_SOCKET_IF_INVALID_MESSAGE` _(TRUE by default)_ - If set to true, every client that sends an unrecognized message will be disconnected from the server. This can happen due to an update or a modification of the client.<br/><br/>
* `MIN_LOGIN_TIMEOUT_MS` _(5.000 by default)_ - The time in milliseconds the client must wait until it can reconnect.<br/><br/>
* `LOGIN_SPAM_BLACKLIST_BAN_MS` _(60.000 by default)_ - The time in milliseconds, the client will be banned from the server if the client reconnects all the time.<br/><br/>
* `MIN_MESSAGE_TIMEOUT_MS` _(500 by default)_ - The time in milliseconds the client must wait until send a new message to the server. If it sends messages too fast, the client will be kicked and temporarily banned.<br/><br/>
* `MESSAGE_SPAM_BLACKLIST_BAN_MS` _(20.000 by default)_ - The time in milliseconds, the client will be banned from the server if the client sends message too fast.<br/><br/>
* `FLAG_REMOVE_TIMEOUT_MS` _(5.000 by default)_ - Every client has flags and if the client reaches 3 or more it will be disconnected and blacklisted. Flags can be gained with message spam up to three times. This value represents the time in milliseconds which must be waited before a flag is being removed.<br/><br/>
* `REGISTRATION_TIMEOUT_MS` _(5.000 by default)_ - The time in milliseconds the client has to register itself with its local username. If not registered in time, the client will be disconnected.<br/><br/>
* `MAX_THREADS_PER_POOL` _(10 by default)_ - The maximum of threads that can be active in one threadpool. Threadpools are used to increase performance.<br/><br/>
* `UPDATE_CLIENTS_INTERVAL_MS` _(10.000 by default)_ - The interval in milliseconds the server will send an update message to all clients with registered and unregistered players. This is useful for the client to determine which player in its surrounding is also connected to the server. This heavily reduces the network load on the server and client.<br/><br/>
* `MAX_CLIENTS` _(50.000 by default)_ - The maximum value of clients that can connect to the server.<br/><br/>
* `MAX_CLIENTS_PER_IP` _(5 by default)_ - The maximum value of clients that can connect to the server per IP.<br/><br/><br/>
* `VOICE_SERVER_PORT` _(24444 by default)_ - The port on which the voice server should be opened on.<br/><br/>
* `MESSAGE_SERVER_PORT` _(23333 by default)_ - The port on which the message server should be opened on.<br/><br/>






