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
It also returns the generated option-ids, which are supposed to be used for voting.

Once you have poll-id, you can monitor the poll change
Get poll result
```http
GET /api/poll/{your-poll-id}
```

Edit poll
```http
PUT /api/poll/{your-poll-id}
{
  "question": "your-question",
  "options": [
     "your option in an array",
     "another option"
   ]
}
```
Delete poll
```http
DELETE /api/poll/{your-poll-id}
```
Vote
```http
POST /api/option/{your-option-id}
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
 (atom {<poll-id> {:user-id <user-id>
                   :question <question>
                   :options 
                   {<option-id> {:vote-count <int>
                                 :option <option>
                                 :rank <int>}
                    ...}}
         ...})

 :votes
 (atom {<option-id> ^ConcurrentLinkedQueue [<user-id> ...]}
        ...})

```
- Why polls and votes are in the separated atoms?

By separating them, the vote(option) endpoint only requires `option-id`, but no need for `poll-id`.<br/>
As a trade-off, this structure makes *read* performance worse, because more computations are needed to collect values.<br/>

- Why `ConcurrentLinkedQueue` instead of `atom`?

`atom(#{})` (or `(atom [])`) could fit the vote count mutable object for this schema. <br/>
I'm not sure about actual benchmarks for this, but theoretically, `ConcurrentLinkedQueue` can outperform in concurrent high-traffic situations because its algorithm aims to reduce the amount of `compareAndSet` calls.<br/>
You can understand this experimental decision as a fun part of my submission :)</br></br>
Additionally, by storing here `user-id`s, we can prevent users from voting for the same option multiple times for free.<br/>
Compared to storing a count number and incrementing it, the trade-off is *read / write* performance.<br/>
(`ConcurrentLinkedQueue.add()` should give higher throughput than `(swap! cnt inc)` with concurrent calls, but `ConcurrentLinkedQueue.size()` should not)<br/>
In my consideration, concurrent writes for voting can be a bottleneck for the polling systems, when we have many users, more likely than reads for poll change. 

<br/>

### Potential future enhancements

- Single-selection polls

For real-world use cases, there should be such a type of poll as **single-selection poll** for which a user can vote for only one option. <br/>
Due to the time limit, I couldn't add this feature, and this version *only* supports **multi-selection polls**<br/>

I could implement that with the following structure:
```clojure
;;
;; !!! This is NOT implemented !!!
;;

{:users  {} // omitted

 :polls
 (atom {<poll-id> {:user-id <user-id>
                   :type <single-selection, or multiple-selection>      // added
                   :voted-users (atom #{<user-id> ...})                 // added
                   :question <question>
                   :options 
                   {<option-id> {:vote-count <int>
                                 :option <option>
                                 :rank <int>}
                    ...}}
         ...})

 :votes
 (atom {<option-id> {:poll-id <poll-id>                                 // added
                     :counter ^ConcurrentLinkedQueue [<user-id> ...]}}  // modified
        ...})

```
A (poll-)type should be added to determine whether voting should be restricted to one option.<br/>
The voted-users field is to support it, too.<br/>
The poll-id field can be added to the votes map for faster lookup of the target poll map.<br/>
<br/>
<br/>
<br/>

## Database schema

```sql
/* technical columns except for id (created_at, updated_at, etc)
   were omitted. */

CREATE TABLE users (
  id UUID PRIMARY KEY,
  api_key TEXT NOT NULL,
  name TEXT NOT NULL,
  is_admin Boolean NOT NULL,
  UNIQUE (api_key),
  UNIQUE (name)
)

CREATE TYPE poll_type AS ENUM('single-selection', 'multiple-selection')

CREATE TABLE polls (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users
  question TEXT NOT NULL,
  type poll_type NOT NULL
)

CREATE TABLE options (
  id UUID PRIMARY KEY,
  poll_id UUID NOT NULL REFERENCES polls,
  option TEXT NOT NULL,
  rank INT NOT NULL,
  UNIQUE (poll_id, option)
)

/*
CREATE TABLE votes (
  id UUID PRIMARY KEY,
  option_id UUID NOT NULL REFERENCES options,
  user_id UUID NOT NULL REFERENCES users,
  UNIQUE (option_id, user_id)
)
 */

CREATE TABLE votes (
  id UUID PRIMARY KEY,
  unique_key UUID NOT NULL,
  option_id UUID NOT NULL REFERENCES options,
  user_id UUID NOT NULL REFERENCES users,
  UNIQUE (unique_key, user_id)
)



```
<br/><br/>

### Consideration
These schemas allow to vote with no locks.<br/>
The values in the unique_key field of the votes table should be either poll_id (for single-selection poll) or option_id (for multiple-selection poll).<br/>
The unique constraint to (unique_key, user_id) prevents the violation of business rules.
The INSERT query will be as follows:
```sql
INSERT INTO votes (unique_key, opiton_id, user_id)
SELECT
  CASE WHEN o.type = 'single_selection' THEN p.id ELSE o.id END
  ,'option_id'
  ,'user_id'
FROM options o
JOIN polls p
ON o.poll_id = p.id
WHERE o.id = ?
```
The SELECT query for poll change is as follows:
```sql
SELECT 
  p.question
 ,o.option
 ,o.rank
 ,count(v.id)
FROM polls p
JOIN options o
ON p.id = o.poll_id
JOIN votes v
ON o.id = v.option_id
WHERE p.id = ?
```

If options and votes table have >1M rows, we can have btree index on options.poll_id and votes.option_id.
