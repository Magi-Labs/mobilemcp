package labs.magi.mobilemcp;
interface ICommandService {
 String execute(in String[] argv, int timeoutMs) = 1;
 void destroy() = 16777114;
}
