import java.util.LinkedList;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class BankSimulation {

    private static final int NUM_TELLERS = 3; private static final int NUM_CUSTOMERS = 50;
    private static final Semaphore doorSemaphore = new Semaphore(2); // Only two customers can enter at a time
    private static final Semaphore managerSemaphore = new Semaphore(1); // Only one teller can interact with manager
    private static final Semaphore safeSemaphore = new Semaphore(2); // Only two tellers can access the safe
    private static final ReentrantLock queueLock = new ReentrantLock();
    private static final Condition queueCondition = queueLock.newCondition();
    private static final Queue<Customer> customerQueue = new LinkedList<>();
    private static int customersServed = 0;
    private static final ReentrantLock servedLock = new ReentrantLock();
    private static final ReentrantLock printLock = new ReentrantLock();
    private static final Random random = new Random();

    private static void log(String message) {
        printLock.lock();
        try {
            System.out.println(message);
        } finally {
            printLock.unlock();
        }
    }
    private static final CountDownLatch bankOpenLatch = new CountDownLatch(NUM_TELLERS);

    static class Teller extends Thread {
        private final int tellerId;

        public Teller(int id) {
            this.tellerId = id;
        }

        @Override
        public void run() {
            log("Teller " + tellerId + " is ready to serve.");
            bankOpenLatch.countDown();

            while (true) {
                Customer customer = null;
                queueLock.lock();
                try {
                    while (customerQueue.isEmpty()) {
                        servedLock.lock();
                        try {
                            if (customersServed >= NUM_CUSTOMERS) {
                                log("Teller " + tellerId + " has no more customers and is closing.");
                                return;
                            }
                        } finally {
                            servedLock.unlock();
                        }
                        try {
                            queueCondition.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                    customer = customerQueue.poll();
                    log("Teller " + tellerId + " is serving Customer " + customer.getCustomerId() + ".");
                } finally {
                    queueLock.unlock();
                }
                if (customer != null) {
                    serveCustomer(customer);
                }
            }
        }

        private void serveCustomer(Customer customer) {
            log("Customer " + customer.getCustomerId() + " introduces itself to Teller " + tellerId + ".");
            log("Customer " + customer.getCustomerId() + " asks for a " + 
                (customer.getTransactionType().equals("withdraw") ? "withdrawal" : "deposit") + " transaction.");

            if (customer.getTransactionType().equals("withdraw")) {
                log("Teller " + tellerId + " is handling the withdrawal transaction.");
                // Interact with the manager
                log("Teller " + tellerId + " is going to the manager.");
                try {
                    managerSemaphore.acquire();
                    log("Teller " + tellerId + " is getting the manager's permission.");
                    // Simulate interaction (5-30 ms)
                    Thread.sleep(random.nextInt(26) + 5);
                    log("Teller " + tellerId + " got the manager's permission.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    managerSemaphore.release();
                }
            } else { log("Teller " + tellerId + " is handling the deposit transaction."); }

            // Accessing the safe
            log("Teller " + tellerId + " is going to the safe.");
            try {
                safeSemaphore.acquire();
                log("Teller " + tellerId + " is entering the safe.");
                // Simulate in safe (10-50 ms)
                Thread.sleep(random.nextInt(41) + 10);
                log("Teller " + tellerId + " has completed the transaction in the safe.");
            } catch (InterruptedException e) { Thread.currentThread().interrupt();} 
            finally { safeSemaphore.release(); }

            log("Teller " + tellerId + " informs Customer " + customer.getCustomerId() + " that the transaction is done.");
            customer.getLatch().countDown(); servedLock.lock();
            try {
                customersServed++;
                if (customersServed >= NUM_CUSTOMERS) {
                    queueLock.lock();
                    try { queueCondition.signalAll();} finally { queueLock.unlock(); }
                }
            } finally { servedLock.unlock(); }
        }
    }

    // Customer class
    static class Customer extends Thread {
        private final int customerId; private final String transactionType; private final CountDownLatch latch;

        public Customer(int id) { this.customerId = id; this.transactionType = random.nextBoolean() ? "deposit" : "withdraw"; this.latch = new CountDownLatch(1); }

        public int getCustomerId() { return customerId; }

        public String getTransactionType() { return transactionType; }

        public CountDownLatch getLatch() { return latch; }

        @Override
        public void run() {
            log("Customer " + customerId + " is going to the bank.");
            try {
                bankOpenLatch.await();
                doorSemaphore.acquire();
                log("Customer " + customerId + " has entered the bank.");
                log("Customer " + customerId + " is getting in line.");
                queueLock.lock();
                try { customerQueue.add(this); log("Customer " + customerId + " is waiting in the queue for a teller.");
                    queueCondition.signal();
                } finally { queueLock.unlock(); }
                latch.await();
                log("Customer " + customerId + " is leaving the bank.");
            } catch (InterruptedException e) { Thread.currentThread().interrupt();} 
            finally { doorSemaphore.release(); }
        }
    }

    // Main method to run
    public static void main(String[] args) {
        Teller[] tellers = new Teller[NUM_TELLERS];
        for (int i = 0; i < NUM_TELLERS; i++) {
            tellers[i] = new Teller(i);
            tellers[i].start();
        }
        new Thread(() -> {
            try {
                bankOpenLatch.await();
                log("Bank is now open.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        Customer[] customers = new Customer[NUM_CUSTOMERS];
        for (int i = 0; i < NUM_CUSTOMERS; i++) {
            customers[i] = new Customer(i);
            customers[i].start();
            try { Thread.sleep(random.nextInt(5) + 1);} 
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        for (int i = 0; i < NUM_CUSTOMERS; i++) { try { customers[i].join();} catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

        queueLock.lock();
        try { queueCondition.signalAll();} finally { queueLock.unlock(); }

        for (int i = 0; i < NUM_TELLERS; i++) {
            try { tellers[i].join(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        log("Bank is now closed.");
    }
}
