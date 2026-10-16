#!/usr/bin/env python3
"""Control panel for Flight Operations: start, test, watch and stop the system with buttons instead of commands.

    Windows: double-click Launcher.cmd        Mac / Linux: python3 launcher.py

Each button says what it does and shows the exact command it runs, so the commands can also be learned and run by
hand (README). Needs what the commands need: Java 21, Docker Desktop (databases, Docker Compose, Kubernetes), Git
Bash on Windows (the scripts are bash), Node.js (Postman's command-line runner) - the "Tools" line says what is found.
"""
import os
import queue
import re
import shutil
import signal
import ssl
import subprocess
import sys
import threading
import tkinter as tk
import urllib.request
import webbrowser
from tkinter import messagebox, ttk

ROOT = os.path.dirname(os.path.abspath(__file__))
WINDOWS = os.name == 'nt'
LOCAL_PORTS = (8083, 8093, 8103)
ANSI = re.compile(r'\x1b\[[0-9;]*[A-Za-z]')   # terminal colour codes
NEWMAN_ASSERTIONS = re.compile(r'assertions\D+(\d+)\D+(\d+)')   # summary line: executed, failed


def find_bash():
    if not WINDOWS:
        return shutil.which('bash') or '/bin/bash'
    for candidate in (r'C:\Program Files\Git\bin\bash.exe', r'C:\Program Files (x86)\Git\bin\bash.exe'):
        if os.path.exists(candidate):
            return candidate
    found = shutil.which('bash')
    # WSL's bash.exe in System32 does not see Windows paths the same way: only accept Git's
    return found if found and 'system32' not in found.lower() else None


BASH = find_bash()


def answers(url, timeout=1.5):
    """True if the URL answers 200 (dev certificates are not verified: this only checks that something runs)."""
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    try:
        with urllib.request.urlopen(url, timeout=timeout, context=context) as response:
            return response.status == 200
    except Exception:
        return False


def quiet_ok(args, timeout=8):
    """True if the command runs and exits with 0 (used for the status lights)."""
    try:
        return subprocess.run(args, cwd=ROOT, capture_output=True, timeout=timeout,
                              creationflags=subprocess.CREATE_NO_WINDOW if WINDOWS else 0).returncode == 0
    except Exception:
        return False


class Launcher:

    def __init__(self, window):
        self.window = window
        self.output = queue.Queue()
        self.processes = {}   # task name -> Popen
        window.title('AISafe - Flight Operations - control panel')
        window.geometry('1180x760')
        window.minsize(980, 620)

        style = ttk.Style()
        style.configure('Title.TLabel', font=('Segoe UI', 14, 'bold'))
        style.configure('Help.TLabel', foreground='#555555')
        style.configure('Action.TButton', padding=(8, 4))

        top = ttk.Frame(window, padding=(12, 10, 12, 4))
        top.pack(fill='x')
        ttk.Label(top, text='AISafe · Flight Operations', style='Title.TLabel').pack(side='left')
        self.lights = {}
        lights = ttk.Frame(top)
        lights.pack(side='right')
        for name in ('Docker', 'Kubernetes', 'Local instances', 'Kubernetes service', 'Docker Compose'):
            frame = ttk.Frame(lights)
            frame.pack(side='left', padx=6)
            dot = tk.Label(frame, text='●', fg='#bbbbbb', font=('Segoe UI', 12))
            dot.pack(side='left')
            ttk.Label(frame, text=name).pack(side='left')
            self.lights[name] = dot

        tools = ttk.Frame(window, padding=(12, 0, 12, 6))
        tools.pack(fill='x')
        self.tools_label = ttk.Label(tools, text='Tools: checking...', style='Help.TLabel')
        self.tools_label.pack(side='left')

        body = ttk.PanedWindow(window, orient='horizontal')
        body.pack(fill='both', expand=True, padx=12, pady=(0, 12))
        left = ttk.Frame(body)
        right = ttk.Frame(body)
        body.add(left, weight=2)
        body.add(right, weight=3)
        self.build_actions(left)
        self.build_output(right)

        self.window.after(100, self.drain_output)
        self.check_tools()
        self.refresh_status()
        window.protocol('WM_DELETE_WINDOW', self.on_close)

    # ------------------------------------------------------------------ layout

    def build_actions(self, parent):
        canvas = tk.Canvas(parent, highlightthickness=0)
        scroll = ttk.Scrollbar(parent, orient='vertical', command=canvas.yview)
        inner = ttk.Frame(canvas)
        inner.bind('<Configure>', lambda e: canvas.configure(scrollregion=canvas.bbox('all')))
        canvas.create_window((0, 0), window=inner, anchor='nw')
        canvas.configure(yscrollcommand=scroll.set)
        canvas.pack(side='left', fill='both', expand=True)
        scroll.pack(side='right', fill='y')
        # mouse wheel scrolls the actions while the pointer is over them
        wheel = lambda e: canvas.yview_scroll(-1 if (e.delta > 0 or e.num == 4) else 1, 'units')
        canvas.bind('<Enter>', lambda e: (canvas.bind_all('<MouseWheel>', wheel), canvas.bind_all('<Button-4>', wheel),
                                          canvas.bind_all('<Button-5>', wheel)))
        canvas.bind('<Leave>', lambda e: [canvas.unbind_all(k) for k in ('<MouseWheel>', '<Button-4>', '<Button-5>')])

        self.https = tk.BooleanVar(value=False)
        self.no_docker = tk.BooleanVar(value=False)
        self.with_teammates = tk.BooleanVar(value=False)

        start = self.section(inner, '1. Start')
        self.action(start, 'Run 3 instances on this computer',
                    'Test mode: built-in aircraft and route data, so the other two services are not needed. '
                    'Ready when the output says READY FOR TESTS. Keeps running until you stop it.',
                    self.cmd_local, task='local instances')
        options = ttk.Frame(start)
        options.pack(fill='x', padx=18, pady=(0, 6))
        ttk.Checkbutton(options, text='HTTPS (TLS)', variable=self.https).pack(side='left')
        ttk.Checkbutton(options, text='without Docker (data in memory)', variable=self.no_docker).pack(side='left', padx=12)

        self.action(start, 'Start the whole system with Docker Compose',
                    '3 services x 3 instances, each Flight Operations instance with its own PostgreSQL, load balancer '
                    'on https://localhost:8443. Needs the three repositories side by side and a lot of memory.',
                    lambda: ['docker', 'compose', 'up', '--build', '-d'], task='docker compose')
        self.action(start, 'Start on Kubernetes (auto-scaling 3 to 5 instances)',
                    'Docker Desktop with Kubernetes enabled. Address: https://localhost:9443. First start takes a few '
                    'minutes.', self.cmd_kubernetes, task='kubernetes')
        ttk.Checkbutton(start, text="also the teammates' services (1 instance each)",
                        variable=self.with_teammates).pack(anchor='w', padx=18, pady=(0, 6))

        see = self.section(inner, '2. See and test')
        self.action(see, 'Open the dashboard',
                    'Opens the live dashboard of whatever is running (sign in as admin / admin123).',
                    None, handler=self.open_dashboard)
        self.action(see, 'Run the Postman tests',
                    'Runs the whole Postman collection against the 3 local instances (start them first). '
                    'Takes about a minute; folder 03 stops instance 2 and waits for it to come back.',
                    self.cmd_postman, task='postman')
        self.action(see, 'Run the automated tests (JUnit)',
                    'All the JUnit tests, a few minutes. No Docker needed. Stop the local instances first: they '
                    'lock the build.', self.cmd_unit_tests, task='unit tests')
        self.action(see, 'Generate load on Kubernetes (2 minutes)',
                    'k6 with 30 users against https://localhost:9443. Watch the dashboard: the auto-scaler adds '
                    'instances when CPU passes 70 %. Uses a lot of memory.', self.cmd_load, task='load')
        self.action(see, 'Show the Kubernetes pods',
                    'Pods, auto-scaler and services in the namespace aisafe.',
                    lambda: ['kubectl', '-n', 'aisafe', 'get', 'pods,hpa,svc'], task='kubectl')

        stop = self.section(inner, '3. Stop')
        self.action(stop, 'Stop the local instances',
                    'Stops the 3 instances started on this computer (their databases keep their data).',
                    None, handler=self.stop_local)
        self.action(stop, 'Stop Docker Compose',
                    'Stops and removes the containers; the databases keep their data (named volumes).',
                    lambda: ['docker', 'compose', 'down'], task='docker compose down')
        self.action(stop, 'Remove everything from Kubernetes',
                    'Deletes the namespace aisafe, including its databases.',
                    lambda: [BASH, 'k8s/deploy.sh', '--down'], task='kubernetes down', confirm=True)

    def section(self, parent, title):
        frame = ttk.LabelFrame(parent, text=title, padding=(8, 6))
        frame.pack(fill='x', pady=(0, 10), padx=(0, 6))
        return frame

    def action(self, parent, title, help_text, command, task=None, handler=None, confirm=False):
        row = ttk.Frame(parent)
        row.pack(fill='x', pady=(2, 6))
        click = handler or (lambda: self.run(task, command(), confirm))
        ttk.Button(row, text=title, style='Action.TButton', command=click).pack(anchor='w')
        ttk.Label(row, text=help_text, style='Help.TLabel', wraplength=420, justify='left').pack(anchor='w', padx=4)

    def build_output(self, parent):
        bar = ttk.Frame(parent)
        bar.pack(fill='x')
        ttk.Label(bar, text='Output', font=('Segoe UI', 10, 'bold')).pack(side='left')
        ttk.Button(bar, text='Clear', command=lambda: self.text.delete('1.0', 'end')).pack(side='right')
        self.running_label = ttk.Label(bar, text='Nothing running', style='Help.TLabel')
        self.running_label.pack(side='right', padx=10)
        self.text = tk.Text(parent, wrap='none', bg='#1e1f24', fg='#e6e6e6', insertbackground='#e6e6e6',
                            font=('Consolas', 10), padx=8, pady=6)
        yscroll = ttk.Scrollbar(parent, orient='vertical', command=self.text.yview)
        xscroll = ttk.Scrollbar(parent, orient='horizontal', command=self.text.xview)
        self.text.configure(yscrollcommand=yscroll.set, xscrollcommand=xscroll.set)
        xscroll.pack(side='bottom', fill='x')
        yscroll.pack(side='right', fill='y')
        self.text.pack(fill='both', expand=True, pady=(4, 0))
        self.text.tag_configure('command', foreground='#9cc4ff')
        self.text.tag_configure('ready', foreground='#7ee787')
        self.text.tag_configure('error', foreground='#ff8a80')
        self.say('Choose an action on the left. Each one shows here the command it runs.\n', 'command')

    # ------------------------------------------------------------------ commands

    def cmd_local(self):
        args = [BASH, 'scripts/run-local.sh', '--stub']
        if self.https.get():
            args.append('--tls')
        if self.no_docker.get():
            args.append('--h2')
        return args

    def cmd_kubernetes(self):
        if self.with_teammates.get():
            return [BASH, '-c', 'OTHER_INSTANCES=1 ./k8s/deploy.sh']
        return [BASH, 'k8s/deploy.sh', '--stub']

    def cmd_postman(self):
        npx = 'npx.cmd' if WINDOWS else 'npx'
        args = [npx, '-y', 'newman@6.2.1', 'run', 'postman/flight-operations.postman_collection.json', '--color', 'off']
        if self.https.get():
            args += ['-e', 'postman/local-https.postman_environment.json', '--ssl-extra-ca-certs', 'certs/ca.crt']
        else:
            args += ['-e', 'postman/local.postman_environment.json']
        return args

    def cmd_unit_tests(self):
        return [os.path.join(ROOT, 'mvnw.cmd' if WINDOWS else 'mvnw'), '-B', 'test']

    def cmd_load(self):
        mount = os.path.join(ROOT, 'loadtest') + ':/scripts'
        return ['docker', 'run', '--rm', '-v', mount, '-e', 'TARGETS=https://host.docker.internal:9443',
                '-e', 'VUS=30', '-e', 'DURATION=120s', 'grafana/k6:latest', 'run', '--quiet', '/scripts/flightops.js']

    # ------------------------------------------------------------------ running

    def run(self, task, args, confirm=False):
        if args is None or (args and args[0] is None):
            messagebox.showerror('Missing tool', 'Git Bash was not found. Install Git for Windows (git-scm.com).')
            return
        if task in self.processes and self.processes[task].poll() is None:
            messagebox.showinfo('Already running', f'"{task}" is still running. Stop it first, or wait.')
            return
        if confirm and not messagebox.askyesno('Are you sure?', 'This deletes data. Continue?'):
            return
        shown = ' '.join('"Git Bash"' if a == BASH else a for a in args)
        self.say(f'\n$ {shown}\n', 'command')
        try:
            process = subprocess.Popen(
                args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                text=True, encoding='utf-8', errors='replace', bufsize=1,
                creationflags=(subprocess.CREATE_NEW_PROCESS_GROUP | subprocess.CREATE_NO_WINDOW) if WINDOWS else 0,
                start_new_session=not WINDOWS)
        except FileNotFoundError:
            self.say(f'Not found: {args[0]} - is it installed and on the PATH?\n', 'error')
            return
        self.processes[task] = process
        threading.Thread(target=self.pump, args=(task, process), daemon=True).start()
        self.update_running()

    def pump(self, task, process):
        assertions = None   # (executed, failed) from newman's summary
        for line in process.stdout:
            line = ANSI.sub('', line)
            found = NEWMAN_ASSERTIONS.search(line)
            if found:
                assertions = (int(found.group(1)), int(found.group(2)))
            self.output.put((line, 'ready' if 'READY' in line else ('error' if 'ERROR' in line else None)))
        code = process.wait()
        if task == 'postman' and assertions:
            # newman also exits with 1 when requests can't connect: folder 03 stops instance 2 on purpose
            executed, failed = assertions
            if failed == 0:
                self.output.put((f'\nPostman: all {executed} checks passed.\n'
                                 f'(Any "ECONNREFUSED ...:8093" above is instance 2 being stopped on purpose by '
                                 f'folder 03.)\n', 'ready'))
            else:
                self.output.put((f'\nPostman: {failed} of {executed} checks FAILED - see the list above.\n', 'error'))
        else:
            self.output.put((f'[{task}] finished (exit code {code})\n', 'command' if code == 0 else 'error'))
        self.output.put((None, 'update'))

    def drain_output(self):
        try:
            while True:
                line, tag = self.output.get_nowait()
                if tag == 'tools':
                    self.tools_label.configure(text=line)
                elif tag == 'status':
                    self.show_status(line)
                elif line is None:
                    self.update_running()
                else:
                    self.say(line, tag)
        except queue.Empty:
            pass
        self.window.after(100, self.drain_output)

    def say(self, line, tag=None):
        at_end = self.text.yview()[1] > 0.98
        self.text.insert('end', line, tag)
        if at_end:
            self.text.see('end')

    def update_running(self):
        names = [name for name, p in self.processes.items() if p.poll() is None]
        self.running_label.configure(text='Running: ' + ', '.join(names) if names else 'Nothing running')

    def kill(self, process):
        if WINDOWS:
            # the whole tree: bash and the java processes it started (by PID, never by name)
            subprocess.run(['taskkill', '/F', '/T', '/PID', str(process.pid)], capture_output=True,
                           creationflags=subprocess.CREATE_NO_WINDOW)
        else:
            os.killpg(process.pid, signal.SIGTERM)

    def stop_local(self):
        process = self.processes.get('local instances')
        if process and process.poll() is None:
            self.say('\nStopping the local instances...\n', 'command')
            self.kill(process)
            return
        if any(answers(f'{s}://localhost:{port}/actuator/health') for port in LOCAL_PORTS for s in ('http', 'https')):
            messagebox.showinfo('Started elsewhere', 'The local instances were not started from this panel. '
                                'Stop them where they were started (Ctrl+C in their terminal, or in VS Code).')
        else:
            self.say('\nNo local instances are running.\n')

    def open_dashboard(self):
        candidates = [('https://localhost:9443', 'Kubernetes'), ('https://localhost:8443', 'Docker Compose'),
                      ('https://localhost:8083', 'local instances (HTTPS)'), ('http://localhost:8083', 'local instances')]
        for base, what in candidates:
            if answers(base + '/actuator/health'):
                self.say(f'\nOpening the dashboard of the {what}: {base}/dashboard (sign in as admin / admin123)\n',
                         'command')
                webbrowser.open(base + '/dashboard')
                return
        messagebox.showinfo('Nothing running', 'Start the system first (section 1).')

    # ------------------------------------------------------------------ status

    def check_tools(self):
        def work():
            found = {
                'Git Bash' if WINDOWS else 'bash': BASH is not None,
                'Java': shutil.which('java') is not None,
                'Docker': shutil.which('docker') is not None,
                'kubectl': shutil.which('kubectl') is not None,
                'Node.js': shutil.which('npx') is not None or shutil.which('npx.cmd') is not None,
            }
            text = 'Tools: ' + '   '.join(f'{"✓" if ok else "✗ missing:"} {name}' for name, ok in found.items())
            self.output.put((text, 'tools'))   # widgets are only touched by the main thread (drain_output)
        threading.Thread(target=work, daemon=True).start()

    def refresh_status(self):
        def work():
            states = {
                'Docker': quiet_ok(['docker', 'info']),
                'Kubernetes': quiet_ok(['kubectl', 'get', 'nodes', '--request-timeout=4s']),
                'Local instances': any(answers(f'{s}://localhost:8083/actuator/health') for s in ('http', 'https')),
                'Kubernetes service': answers('https://localhost:9443/actuator/health'),
                'Docker Compose': answers('https://localhost:8443/actuator/health'),
            }
            self.output.put((states, 'status'))
        threading.Thread(target=work, daemon=True).start()
        self.status_timer = self.window.after(5000, self.refresh_status)

    def show_status(self, states):
        for name, ok in states.items():
            self.lights[name].configure(fg='#1f9d55' if ok else '#c9c9c9')

    def on_close(self):
        running = [name for name, p in self.processes.items() if p.poll() is None]
        if running and not messagebox.askyesno('Still running', 'Stop ' + ', '.join(running) + ' and close?'):
            return
        for name in running:
            self.kill(self.processes[name])
        self.window.destroy()


if __name__ == '__main__':
    if WINDOWS:
        try:   # sharp text and right sizes when Windows scales the display (125 %, 150 %...)
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    root = tk.Tk()
    Launcher(root)
    root.mainloop()
