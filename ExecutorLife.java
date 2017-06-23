/*
    Life.java

    Graphical implementation of Conway's game of Life.

    Currently single-threaded, but has infrastructure for multithreaded
    solutions.

    Michael L. Scott, November 2016, based on earlier versions from
    1998, 2007, and 2011.

    Updated by Emily Michel 12/14/2016 to handle concurrency via Executors
 */

import java.awt.*;          // older of the two standard Java GUIs
import java.awt.event.*;
import javax.swing.*;
import java.lang.Thread.*;
import java.util.Collection;
import java.util.ArrayList;
//adding:
import java.util.concurrent.*; 


public class ExecutorLife {
    //changed from private to public: 
    public static final int n = 100;    // number of cells on a side
    private static int pauseIterations = -(500000000/n/n);
        // nanoseconds per dot for a delay of about a half a second
    //changed from private to public 
    public static long numThreads = 1;
        // I currently don't do anything with this variable.
        // You should.
    private static boolean headless = false;    // don't create GUI
    private static boolean glider = false;      // create initial glider

    private UI buildUI(RootPaneContainer pane) {
        return new UI(n, pane, pauseIterations, headless, glider);
    }

    // Print error message and exit.
    //
    private static void die(String msg) {
        System.err.print(msg);
        System.exit(-1);
    }

    // Examine command-line arguments for non-default parameters.
    //
    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("-t")) {
                if (++i >= args.length) {
                    die("Missing number of threads\n");
                } else {
                    int nt = -1;
                    try {
                        nt = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) { }
                    if (nt > 0) {
                        numThreads = nt;
                    } else {
                        die(String.format("Invalid number of threads: %s\n",
                                          args[i]));
                    }
                }
            } else if (args[i].equals("-s")) {
                if (++i >= args.length) {
                    die("Missing number of spin iterations\n");
                } else {
                    int di = -1;
                    try {
                        di = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) { }
                    if (di > 0) {
                        pauseIterations = di;
                    } else {
                        die(String.format("Invalid number of spin iterations: %s\n",
                                          args[i]));
                    }
                }
            } else if (args[i].equals("--headless")) {
                headless = true;
            } else if (args[i].equals("--glider")) {
                glider = true;
            } else {
                die(String.format("Unexpected argument: %s\n", args[i]));
            }
        }
    }

    public static void main(String[] args) {
        parseArgs(args);
        ExecutorLife me = new ExecutorLife();
        JFrame f = new JFrame("Life");
        f.addWindowListener(new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            System.exit(0);
          }
        });
        UI ui = me.buildUI(f);
        if (headless) {
            ui.onRunClick();
        } else {
          f.pack();
          f.setVisible(true);
        }
    }
}

//new Runnable- (will be invoked by executor) to control the order of tasks that update the board 
class TaskController implements Runnable {
    private final LifeBoard lb;
    private final Coordinator c;
    private final UI u;
    //executor that controls Runnables (including this one):
    private ExecutorService exec; 
    //tasks that will update the board:
    private Collection<Callable<Object>> tasks;

    public void run() {
        //keep repeating (keep doing generations)
        while(true){
            c.register();
            try{
                //Have all tasks update the board. This returns when ALL tasks are done
                //otherwise we could try to swap boards before all threads were complete
                exec.invokeAll(tasks);
            } catch (InterruptedException e){ //if thread interrupted before tasks finished, so were cancelled
                System.out.println(e.getMessage()); 
            }
            //all tasks are done updating board. Can now swap boards, and increment generation/report time or repaint
            lb.finishGeneration(); 
            c.unregister(); 
        }            
    }

    // Constructor
    public TaskController(LifeBoard LB, Coordinator C, UI U, ExecutorService e, Collection<Callable<Object>> t) {
        lb = LB;
        c = C;
        u = U;
        exec = e;
        tasks = t;
    }
}


// The Worker is the thread that does the actual work of calculating new
// generations.
//
//This originally was Worker extends thread, calling it Task instead and making it Runnable: 
class Task implements Runnable {  
    private final LifeBoard lb;
    private final Coordinator c;
    private final UI u;
    //bounds for which part of array this task deals with 
    private int begin;
    private int end;
    //name of task (for debugging)
    public int name;

    // The run() method of a Java Thread is never invoked directly by
    // user code.  Rather, it is called by the Java runtime when user
    // code calls start().
    //
    // The run() method of a worker thread *must* begin by calling
    // c.register() and end by calling c.unregister().  These allow the
    // user interface (via the Coordinator) to pause and terminate
    // workers.  Note how the worker is set up to catch KilledException.
    // In the process of unwinding back to here we'll cleanly and
    // automatically release any monitor locks.  If you create new kinds
    // of workers (as part of a parallel player), make sure they call
    // c.register() and c.unregister() properly.
    //
    public void run() {
        try {
            c.register();
            try {
            //want run to deal with a single generation instead of all at once-> get rid of while loop. 
            //    while (true) {
                    lb.doGeneration(begin, end, name);
            //    }
            } catch(Coordinator.KilledException e) {}
        } finally {
            c.unregister();
        }
    }

    // Constructor
    //
    public Task(LifeBoard LB, Coordinator C, UI U, int B, int E, int nm) {
        lb = LB;
        c = C;
        u = U;
        //bounds for part of board it deals with 
        begin = B;
        end = E;
        //(for debugging):
        name = nm;
    }
}

// The LifeBoard is the Life world, containing all the cells.
// It embeds all knowledge about how to display things graphically.
//
class LifeBoard extends JPanel { 
    private static final int width = 800;      // canvas dimensions
    private static final int height = 800;
    private static final int dotsize = 6;
    private static final int border = dotsize;
    static  boolean headless = false;
    private int B[][];  // board contents
    private int A[][];  // scratch board
    private int T[][];  // temporary pointer
    private int generation = 0;

    // following fields are set by constructor:
    private final Coordinator c;
    private final UI u;
    private final int n;  // number of cells on a side

    // Called by the UI when it wants to start over.
    //
    public void clear() {
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                B[i][j] = 0;
            }
        }
        repaint();
            // tell graphic system that LifeBoard needs to be re-rendered
    }

    // This is the function that actually plays (one full generation of)
    // the game.  It is called by the run() method of Thread class
    // Worker.  You'll want to replace this with something that does
    // only part of a generation, so it can be called from multiple
    // Workers concurrently.  Make sure all of your threads call
    // c.register() when they start work, and c.unregister() when
    // they finish, so the Coordinator can manage them.
    //
    public void doGeneration(int begin, int end, int name) throws Coordinator.KilledException {
    //    System.out.println("Start Thread " + name + " : " + begin + " to " + end + ".");
        for (int i = begin; i < end; i++) {
            for (int j = 0; j < n; j++) {

                // NOTICE: you are REQUIRED to call hesitate() EVERY TIME
                // you update a LifeBoard cell.  The call serves two
                // purposes: (1) it checks to see whether you should pause
                // or stop; (2) it introduces delay that allows you to
                // see the board evolving and that will give you the
                // appearance of speedup with additional threads.

                c.hesitate();
                int im = (i+n-1) % n; int ip = (i+1) % n;
                int jm = (j+n-1) % n; int jp = (j+1) % n;
                switch (B[im][jm] + B[im][j] + B[im][jp] +
                        B[i][jm]             + B[i][jp] +
                        B[ip][jm] + B[ip][j] + B[ip][jp]) {
                    case 0 :
                    case 1 : A[i][j] = 0;       break;
                    case 2 : A[i][j] = B[i][j]; break;
                    case 3 : A[i][j] = 1;       break;
                    case 4 :
                    case 5 :
                    case 6 :
                    case 7 :
                    case 8 : A[i][j] = 0;       break;
                }
            }
        }

        //code here has been moved to finishGeneration
    /*    T = B;  B = A;  A = T;
        if (headless) {
            if (generation % 10 == 0) {
                System.out.println("generation " + generation
                    + " done @ " + System.currentTimeMillis());
            }
            ++generation;
        } else {
            repaint ();
        }
            // tell graphic system that LifeBoard needs to be re-rendered
    */
    }

    //Code originally found at end of doGeneration. Increments generation and reports time or repaints board
    public void finishGeneration(){
    //   System.out.println("Inside finishGeneration");
        T = B;  B = A;  A = T;
        if (headless) {
            if (generation % 10 == 0) {
                System.out.println("generation " + generation
                    + " done @ " + System.currentTimeMillis());
            } 
            ++generation;
        //    System.out.println("end of finish generation, headless");
        } else {
            repaint ();
        //    System.out.println("end of finish generation, glider");
        }
            // tell graphic system that LifeBoard needs to be re-rendered
    }


    // The following method is called automatically by the graphics
    // system when it thinks the LifeBoard canvas needs to be
    // re-displayed.  This can happen because code elsewhere in this
    // program called repaint(), or because of hiding/revealing or
    // open/close operations in the surrounding window system.
    //
    public void paintComponent(Graphics g) {
      if (headless) {
        return;
      }
        final Graphics2D g2 = (Graphics2D) g;

        super.paintComponent(g);    // clears panel

        // The following is synchronized to avoid race conditions with
        // worker threads.
        synchronized (u) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    drawSpot (i, j, g);
                }
            }
        }
    }

    public void toggleClick (int mx, int my) {
        Dimension d = (getSize ());
        int x = n * mx / d.width;
        int y = n * my / d.height;
        Graphics g = getGraphics ();
        mx = d.width * x / n;       // round to nearest spot center
        my = d.height * y / n;      // round to nearest spot center
        B[x][y] = 1 - B[x][y];
        drawSpot (x, y, g);
        g.dispose ();   // reclaim resources eagerly
    }

    private void drawSpot (int x, int y, Graphics g) {
        Dimension d = (getSize());
        int mx = d.width * x / n;       // round to nearest spot center
        int my = d.height * y / n;      // round to nearest spot center
        if (B[x][y] == 1) {
            g.setColor(Color.blue);
        } else {
            g.setColor(getBackground ());
        }
        g.fillOval (mx, my, dotsize, dotsize);
    }

    // Constructor
    //
    public LifeBoard(int N, Coordinator C, UI U,
                     boolean hdless, boolean glider) {
        n = N;
        c = C;
        u = U;
        headless = hdless;

        A = new int[n][n];  // initialized to all 0
        B = new int[n][n];  // initialized to all 0

        setPreferredSize(new Dimension(width+border*2, height+border*2));
        setBackground(Color.white);
        setForeground(Color.black);

        clear();

        if (glider) {
            // create an initial glider in the upper left corner
            B[0][1] = B[1][2] = B[2][0] = B[2][1] = B[2][2] = 1;
        }
    }
}

// Class UI is the user interface.  It displays a LifeBoard canvas above
// a row of buttons.  Actions (event handlers) are defined for each
// of the buttons.  Depending on the state of the UI, either the "run" or
// the "pause" button is the default (highlighted in most window
// systems); it will often self-push if you hit carriage return.
//
class UI extends JPanel {
    private final Coordinator c;
    private final LifeBoard lb;

    private final JRootPane root;
    private static final int externalBorder = 6;

    private static final int stopped = 0;
    private static final int running = 1;
    private static final int paused = 2;

    private int state = stopped;

    // Constructor
    //
    public UI(int N, RootPaneContainer pane, int pauseIterations,
              boolean headless, boolean glider) {
        final UI u = this;
        c = new Coordinator(pauseIterations);
        lb = new LifeBoard(N, c, u, headless, glider);

        final JPanel b = new JPanel();   // button panel

        final JButton runButton = new JButton("Run");
        final JButton pauseButton = new JButton("Pause");
        final JButton stopButton = new JButton("Stop");
        final JButton clearButton = new JButton("Clear");
        final JButton quitButton = new JButton("Quit");

        // Note that the addListener calls below pass an annonymous
        // inner class as argument.

        lb.addMouseListener(new MouseListener() {
            public void mouseClicked(MouseEvent e) {
                if (state == stopped) {
                    lb.toggleClick(e.getX(), e.getY());
                } // else do nothing
            }
            public void mouseEntered(MouseEvent e) { }
            public void mouseExited(MouseEvent e) { }
            public void mousePressed(MouseEvent e) { }
            public void mouseReleased(MouseEvent e) { }
        });
        runButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (state == stopped) {
                    state = running;
                    root.setDefaultButton(pauseButton);
                    onRunClick();
                } else if (state == paused) {
                    state = running;
                    root.setDefaultButton(pauseButton);
                    c.toggle();
                }
            }
        });
        pauseButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (state == running) {
                    state = paused;
                    root.setDefaultButton(runButton);
                    c.toggle();
                }
            }
        });
        stopButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                state = stopped;
                c.stop();
                root.setDefaultButton(runButton);
            }
        });
        clearButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                state = stopped;
                c.stop();
                root.setDefaultButton(runButton);
                lb.clear();
            }
        });
        quitButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                System.exit(0);
            }
        });

        // put the buttons into the button panel:
        b.setLayout(new FlowLayout());
        b.add(runButton);
        b.add(pauseButton);
        b.add(stopButton);
        b.add(clearButton);
        b.add(quitButton);

        // put the LifeBoard canvas and the button panel into the UI:
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(
            externalBorder, externalBorder, externalBorder, externalBorder));
        add(lb);
        add(b);

        // put the UI into the Frame
        pane.getContentPane().add(this);
        root = getRootPane();
        root.setDefaultButton(runButton);
    }

    public void onRunClick() {
        //number of threads that update the board:
        int T = (int)ExecutorLife.numThreads;
        //number of tasks: change?
        int K = T + T/2; //1.5*T
        //exec is an Executor with T + 1 behind it. at most that many threads will be operating 
        //(adding 1 for the controller thread)
        ExecutorService exec = Executors.newFixedThreadPool(T+1); 

        //System.out.println("Num threads = " + T + "."); 
        //Create tasks, each updates part of board:
        Collection<Callable<Object>> tasks = generateTasks(K);
        //Task that controls order of the board updating tasks, but uses same executor
        TaskController control = new TaskController(lb, c, this, exec, tasks);
        exec.execute(control); 
    }

    //Generate Collection of tasks, each updates part of board
    public Collection<Callable<Object>> generateTasks(int numTasks){
        Collection<Callable<Object>> tasks = new ArrayList<Callable<Object>>(numTasks);
        //Code for splitting up board from Sarah's code with threads
        //increment used for figuring out how much of board to use
        double inc = ((double)ExecutorLife.n) / ((double)numTasks);
        for (int i = 0; i < numTasks; i++){ 
            //note: i will be "name" of the thread for debugging)
            if(i+1 ==  numTasks){ //on last task
                Task temp = new Task(lb, c, this, (int)(i*inc), ExecutorLife.n, i);
                Callable<Object> callme = Executors.callable(temp);
                tasks.add(callme);
            } else {
                Task temp = new Task(lb, c, this, (int)(i*inc), (int)((i+1)*inc), i);
                Callable<Object> callme = Executors.callable(temp);
                tasks.add(callme);
            }
        }
        return tasks;
    } 
}