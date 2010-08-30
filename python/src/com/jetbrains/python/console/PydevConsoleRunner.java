package com.jetbrains.python.console;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionHelper;
import com.intellij.execution.console.LanguageConsoleImpl;
import com.intellij.execution.console.LanguageConsoleViewImpl;
import com.intellij.execution.process.CommandLineArgumentsProvider;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.runners.AbstractConsoleRunnerWithHistory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.encoding.EncodingManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.impl.source.codeStyle.Helper;
import com.intellij.psi.impl.source.codeStyle.HelperFactory;
import com.intellij.util.net.NetUtils;
import com.jetbrains.django.run.Runner;
import com.jetbrains.python.PythonFileType;
import com.jetbrains.python.PythonHelpersLocator;
import com.jetbrains.python.console.pydev.ICallback;
import com.jetbrains.python.console.pydev.InterpreterResponse;
import com.jetbrains.python.console.pydev.PydevConsoleCommunication;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/**
 * @author oleg
 */
public class PydevConsoleRunner extends AbstractConsoleRunnerWithHistory {
  private final int[] myPorts;
  private PydevConsoleCommunication myPydevConsoleCommunication;
  public static Key<PydevConsoleCommunication> CONSOLE_KEY = new Key<PydevConsoleCommunication>("PYDEV_CONSOLE_KEY");
  private static final String PYTHON_ENV_COMMAND = "import sys; print('Python %s on %s' % (sys.version, sys.platform))\n";
  private Helper myHelper;
  private int currentPythonIndentSize;

  protected PydevConsoleRunner(@NotNull final Project project,
                               @NotNull final String consoleTitle,
                               @NotNull final CommandLineArgumentsProvider provider,
                               @Nullable final String workingDir,
                               int[] ports) {
    super(project, consoleTitle, provider, workingDir);
    myPorts = ports;
    myHelper = HelperFactory.createHelper(PythonFileType.INSTANCE, myProject);
    currentPythonIndentSize = CodeStyleSettingsManager.getSettings(myProject).getIndentSize(PythonFileType.INSTANCE);
  }

  public static void run(@NotNull final Project project,
                         @NotNull final Sdk sdk,
                         final String consoleTitle,
                         final String projectRoot,
                         final String ... statements2execute) {
    final int[] ports;
    try {
      // File "pydev/console/pydevconsole.py", line 223, in <module>
      // port, client_port = sys.argv[1:3]
      ports = NetUtils.findAvailableSocketPorts(2);
    }
    catch (IOException e) {
      ExecutionHelper.showErrors(project, Arrays.<Exception>asList(e), consoleTitle, null);
      return;
    }
    final ArrayList<String> args = new ArrayList<String>(
      Arrays.asList(sdk.getHomePath(), "-u", PythonHelpersLocator.getHelperPath("pydev/console/pydevconsole.py")));
    for (int port : ports) {
      args.add(String.valueOf(port));
    }
    final CommandLineArgumentsProvider provider = new CommandLineArgumentsProvider() {
      public String[] getArguments() {
        return args.toArray(new String[args.size()]);
      }

      public boolean passParentEnvs() {
        return false;
      }

      public Map<String, String> getAdditionalEnvs() {
        return Collections.emptyMap();
      }
    };

    final PydevConsoleRunner consoleRunner = new PydevConsoleRunner(project, consoleTitle, provider, projectRoot, ports);
    try {
      consoleRunner.initAndRun(statements2execute);
    }
    catch (ExecutionException e) {
      ExecutionHelper.showErrors(project, Arrays.<Exception>asList(e), consoleTitle, null);
    }
  }

  @Override
  protected LanguageConsoleViewImpl createConsoleView() {
    return new PydevLanguageConsoleView(myProject, myConsoleTitle);
  }

  @Override
  protected Process createProcess() throws ExecutionException {
    final Process server = Runner.createProcess(myWorkingDir, true, myProvider.getAdditionalEnvs(), myProvider.getArguments());
    try {
      myPydevConsoleCommunication = new PydevConsoleCommunication(getProject(), myPorts[0], server, myPorts[1]);
    }
    catch (Exception e) {
      throw new ExecutionException(e.getMessage());
    }
    return server;
  }
  protected PyConsoleProcessHandler createProcessHandler(final Process process) {
    final Charset outputEncoding = EncodingManager.getInstance().getDefaultCharset();
    return new PyConsoleProcessHandler(process, myConsoleView.getConsole(), getProviderCommandLine(myProvider), outputEncoding);
  }

  public void initAndRun(final String ... statements2execute) throws ExecutionException {
    super.initAndRun();

    // Propagate console communication to language console
    ((PydevLanguageConsoleView)myConsoleView).setPydevConsoleCommunication(myPydevConsoleCommunication);

    try {
      Thread.sleep(300);
    }
    catch (InterruptedException e) {
      // Ignore
    }

    // Make executed statements visible to developers
    final LanguageConsoleImpl console = myConsoleView.getConsole();
    PyConsoleHighlightingUtil.processOutput(console, PYTHON_ENV_COMMAND, ProcessOutputTypes.SYSTEM);
    sendInput(PYTHON_ENV_COMMAND);
    for (String statement : statements2execute) {
      PyConsoleHighlightingUtil.processOutput(console, statement + "\n", ProcessOutputTypes.SYSTEM);
      sendInput(statement+"\n");
    }
  }

  @Override
  protected AnAction createStopAction() {
    final AnAction generalStopAction = super.createStopAction();
    final AnAction stopAction = new AnAction() {
      @Override
      public void update(AnActionEvent e) {
        generalStopAction.update(e);
      }

      @Override
      public void actionPerformed(AnActionEvent e) {
        if (myPydevConsoleCommunication != null) {
          final AnActionEvent furtherActionEvent =
            new AnActionEvent(e.getInputEvent(), e.getDataContext(), e.getPlace(),
                              e.getPresentation(), e.getActionManager(), e.getModifiers());
          try {
            myPydevConsoleCommunication.close();
          }
          catch (Exception e1) {
            // Ignore
          }
          generalStopAction.actionPerformed(furtherActionEvent);
        }
      }
    };
    stopAction.copyFrom(generalStopAction);
    return stopAction;
  }

  @Override
  public void sendInput(final String input) {
    if (myPydevConsoleCommunication != null){
      final boolean waitedForInputBefore = myPydevConsoleCommunication.waitingForInput;
      myPydevConsoleCommunication.execInterpreter(input, new ICallback<Object, InterpreterResponse>() {
        public Object call(final InterpreterResponse interpreterResponse) {
          final LanguageConsoleImpl console = myConsoleView.getConsole();
          // Handle prompt
          if (interpreterResponse.need_input){
            if (!PyConsoleHighlightingUtil.INPUT_PROMPT.equals(console.getPrompt())){
              console.setPrompt(PyConsoleHighlightingUtil.INPUT_PROMPT);
            }
          }
          else if (interpreterResponse.more){
            if (!PyConsoleHighlightingUtil.INDENT_PROMPT.equals(console.getPrompt())){
              console.setPrompt(PyConsoleHighlightingUtil.INDENT_PROMPT);
              // In this case we can insert indent automatically
              final int indent = myHelper.getIndent(input, false);
              new WriteCommandAction(myProject) {
                @Override
                protected void run(Result result) throws Throwable {
                  EditorModificationUtil.insertStringAtCaret(console.getConsoleEditor(), myHelper.fillIndent(indent + currentPythonIndentSize));
                }
              }.execute();
            }
          } else {
            if (!PyConsoleHighlightingUtil.ORDINARY_PROMPT.equals(console.getPrompt())){
              console.setPrompt(PyConsoleHighlightingUtil.ORDINARY_PROMPT);
            }
          }

          // Handle output
          if (!StringUtil.isEmpty(interpreterResponse.err)){
            PyConsoleHighlightingUtil.processOutput(console, interpreterResponse.err, ProcessOutputTypes.STDERR);
          } else {
            PyConsoleHighlightingUtil.processOutput(console, interpreterResponse.out, ProcessOutputTypes.STDOUT);
          }
          return null;
        }
      });
      // After requesting input we got no call back to change prompt, change it manually
      if (waitedForInputBefore && !myPydevConsoleCommunication.waitingForInput){
        final LanguageConsoleImpl console = myConsoleView.getConsole();
        console.setPrompt(PyConsoleHighlightingUtil.ORDINARY_PROMPT);
      }
    }
  }

  public static boolean isInPydevConsole(final PsiElement element){
    return element instanceof PydevConsoleElement || getConsoleCommunication(element) != null;
  }

  @Nullable
  public static PydevConsoleCommunication getConsoleCommunication(final PsiElement element) {
    return element.getContainingFile().getCopyableUserData(CONSOLE_KEY);
  }
}
