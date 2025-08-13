# Login Time Attack Protector
A lightweight, thread-safe Java library to mitigate user enumeration (account discovery) through login timing attacks.

## The problem: timing attacks on login forms
A common vulnerability in authentication systems is user enumeration via timing analysis. Here's how it works:

* An attacker submits a username and password to a login form.
* If the user does not exist, the server quickly rejects the request.
* If the user exists, the server must perform a computationally expensive password hash comparison (e.g., using BCrypt or Argon2), which takes significantly more time.
* By measuring the server's response time, an attacker can distinguish between invalid usernames and valid usernames with incorrect passwords. This allows them to build a list of valid user accounts in the system, which is a security risk.

## The solution: constant-time response simulation
This library solves the problem by making failed login attempts statistically indistinguishable.

For **valid users**, it measures the time taken to perform the real password verification. It continuously maintains a statistical model (mean and standard deviation) of these timings.

For **non-existent users**, instead of failing immediately, it generates a random delay that statistically mimics a real password check.

This ensures that, from an attacker's perspective, both scenarios take a similar amount of time, rendering the timing attack ineffective.

## Getting started
### Installation
Add the dependency to your project's `pom.xml`:

```xml
<dependency>
    <groupId>com.coreoz</groupId>
    <artifactId>login-time-attack-protector</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Basic usage
Here is a simple, self-contained example of how to protect an authentication method.

First, instantiate the protector and the non-blocking delayer. These should be singletons in your application.
```java
TimingAttackProtector timingAttackProtector = TimingAttackProtector.fromDefaultConfig();
// Optional: initialize timing protector with base time value
String dummyPassword = hashService.hashPassword("dummy password");
timingAttackProtector.measureAndExecute(() -> hashService.checkPassword("wrong-dummy-password", dummyPassword));

DelayedCompleter delayedCompleter = new DelayedCompleter();
```

Next, integrate the logic into your authentication service.
```java
public class AuthenticationService {
    // ... dependencies: userDao, passwordHasher, etc.

    public CompletableFuture<User> authenticate(String username, String password) {
        User foundUser = userDao.findByUsername(username);

        // Case 1: User does not exist
        if (foundUser == null) {
            // Generate a delay that mimics a real password check.
            Duration randomDelay = timingAttackProtector.generateDelay();

            // Return a future that completes with an empty result after the delay.
            return delayedCompleter.waitDuration(randomDelay)
                .thenApply(unused -> null);
        }

        // Case 2: User exists, so we perform the real password check.
        // The 'measureAndExecute' method times the operation and returns its result.
        boolean isPasswordCorrect = timingAttackProtector.measureAndExecute(
            () -> passwordHasher.checkPassword(password, foundUser.getPasswordHash())
        );

        if (isPasswordCorrect) {
            return CompletableFuture.completedFuture(foundUser);
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }
}
```

## How it works
The library consists of two main components that work together.

#### `TimingAttackProtector`
A thread-safe and high performance utility that records the execution time of legitimate operations (like password checks) to build a statistical model.

When an immediate failure occurs (e.g., user not found), you can ask it to generate a random delay that statistically mimics a real operation. This makes it difficult for an attacker to distinguish between fast and slow paths in your code.

#### Configuration
The behavior is controlled via `TimingAttackProtector.Config`:

| Parameter      | Description                                                                                                                                                        | Default |
|:---------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------|:--------|
| `maxSamples`   | The maximum number of recent execution time samples to keep for statistical analysis.                                                                              | `10`    |
| `samplingRate` | The probability (0.0 to 1.0) of recording a new execution time once `maxSamples` has been reached. A value of `1.0` means every new execution replaces the oldest. | `0.22`  |

#### `DelayedCompleter`
A high-performance, non-blocking utility for completing a `CompletableFuture<Void>` after a specified duration. It provides an efficient alternative to `Thread.sleep()` or a thread-per-task model for delayed actions.

It is ideal for scenarios requiring hundreds or thousands of short, non-blocking waits per second without the overhead of creating new threads for each wait.

In environments where an HTTP server is based on virtual threads, this utility might be replaced by a standard `Thread.sleep()` call.

##### Key features
* **High performance**: Uses a single, dedicated worker thread to manage all scheduled tasks.
* **Efficient waiting**: Employs a `PriorityQueue` with `wait`/`notify` to ensure the worker thread sleeps efficiently and only wakes when necessary (no busy-waiting).
* **Thread-safe**: The `waitDuration()` method is fully thread-safe.
* **Resource-aware**: Implements `AutoCloseable` for deterministic cleanup. Using it within a `try-with-resources` block or a managed lifecycle is recommended to ensure the worker thread is properly shut down.

##### Shutdown behavior
When `close()` is called, the worker thread is terminated, and any pending futures are immediately completed exceptionally with an `IllegalStateException`.

## Advanced usage: asynchronous integration with JAX-RS
The `DelayedCompleter` is made for asynchronous web frameworks like Jersey (JAX-RS). The following example shows how to integrate the authentication logic into a resource endpoint.

```java
@POST
@Operation(description = "Authenticate a user and create a session token")
public void authenticate(@Suspended final AsyncResponse asyncResponse, Credentials credentials) {
    authenticateUser(credentials)
        .thenAccept(authenticatedUser -> {
            if (authenticatedUser == null) {
                // Authentication failed => return a static authentication failed object
                // => another option is to throw an exception that will be handle by the global configured ExceptionMapper instance (see bellow)
                asyncResponse.resume(Response.status(Response.Status.UNAUTHORIZED).entity(FAILD_AUTHENTICATION_OBJECT).build());
            } else {
                // Authentication success
                // => build the user session and return it
                asyncResponse.resume(Response.ok(createJwtSession(authenticatedUser)).build());
            }
        })
        .exceptionally(error -> {
            // Exceptions caught here are likely CompletionException
            // Either the exception is handled here directly, and a Response is passed to the asyncResponse object
            // or either, like in this example, the exception is passed to the asyncResponse object so it is handled by the global configured ExceptionMapper instance
            // => so Exception are handled the same way all across the application
            asyncResponse.resume(error);
            return null;
        });
}
```
