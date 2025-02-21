## Instruction

### How to run
To run just locally
```sh
clj -M -m polling-system-api.core [port]
```
Or alternatively, compile to bytecode and run
```sh
make build -B
java -jar ./target/polling_system_api.jar [port]
```
The port number can be passed as an optional argument to fix it.

### APIs
Create poll
```http
POST /api/poll
{
  "poll-id": "your-poll-id",
  "question": "your-question",
  "options": [
     "your options in an array",
     "another option"
   ]
}
```
It also returns the generated option-ids, which are supposed to be used for vote.

Once you have poll-id, you can monitor the poll change
Get poll result
```http
GET /api/poll/your-poll-id
```

Edit poll
```http
PUT /api/poll/your-poll-id
{
  "question": "your-question",
  "options": [
     "your options in an array",
     "another option"
   ]
}
```
Delete poll
```http
DELETE /api/poll/your-poll-id
```
Vote
```http
POST /api/option-id
```
<br/>
<br/>

## About Design
The following features are **added** to the requirements:
- Only registered users can call each endpoint
- 1 vote per 1 user for the same option
  - If a user could vote the same option multiple times, it wouldn't make sense to me, so this rule was added. 
<br/>

### Data structure
In memory db stores data as follows:
```clojure
{:users  {"123adminkey" {:name "admin"
                         :admin? true}
          "123user1" {:name "user1"}
          "123user2" {:name "user2"}}

 :polls
 {<poll-id> {:user-id <user_id>
             :question <question>
             :options 
             {<option-id> {:vote-count <int>
                           :option <option>
                           :rank <int>}
              ...}}
  ...}

 :votes
 {<option-id> ^ConcurrentLinkedQueue [<user-id> ...]}
  ...}

```
- Why polls and votes are in the separated atoms?

Alternatively, I could have them in one `atom`, but in that case, concurrent mutations (`swap!` calls) of create [edit, delete] polls would affect the voting performance.<br/>
When they are separate, they don't affect each other. Because of the nature of the polling API, there can be many concurrent accesses for voting, so here is my consideration.<br>
As a trade-off, this structure makes *read* performance worse, because more computations are needed to collect values.<br/>
However, it is more controllable (poll interval) than *write* (votes) traffic.

- Why `ConcurrentLinkedQueue` instead of `atom`?

`atom(#{})` (or `(atom [])`) could fit the vote count mutable object for this schema. <br/>
I'm not sure about actual benchmarks for this, but theoretically, `ConcurrentLinkedQueue` can outperform in concurrent high-traffic situations because its algorithm aims to reduce the amount of `compareAndSet` calls.<br/>
You can understand this experiment as a fun part of my submission :)</br></br>
Additionally, by storing here `user-id`s, we can prevent users from voting for the same option multiple times.<br/>
Compared to storing count and incrementing it, the trade-off is *read / write* performance.<br/>
(`ConcurrentLinkedQueue.add()` should give higher throughput than `(swap! cnt inc)` with concurrent calls, but `ConcurrentLinkedQueue.size()` should not)


<br/>

### Potential future enhancements

- Single-selection polls

For real-world use cases, there should be such a type of poll as **single-selection poll** for which a user can vote for only one option. <br/>
Due to the time limit, I couldn't add this feature, and this version *only* supports **multi-selection polls**
<br/>
<br/>
<br/>

```sql
/* technical columns except for id (created_at, updated_at, etc)
   were omitted. */

CREATE TABLE users (
  id UUID PRIMARY KEY,
  api_key TEXT,
  name TEXT,
  is_admin Boolean,
  UNIQUE (api_key),
  UNIQUE (name)
)

CREATE TABLE polls (
  id UUID PRIMARY KEY,
  user_id UUID, // fk
  question TEXT,
)

CREATE TABLE options (
  id UUID PRIMARY KEY,
  poll_id UUID, // fk
  option TEXT,
  rank INT,
  UNIQUE (poll_id, option)
)

CREATE TABLE votes (
  id UUID PRIMARY KEY,
  option_id UUID, // fk
  user_id UUID,   // fk
  UNIQUE (option_id, user_id)
)


SELECT 
  p.question,
  p.option,
  count(v.id)
FROM polls p
JOIN options o
ON p.id = o.poll_id
JOIN votes v
ON o.id = v.option_id
WHERE p.id = ?
```
