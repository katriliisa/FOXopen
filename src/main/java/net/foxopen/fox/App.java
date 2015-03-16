package net.foxopen.fox;

import net.foxopen.fox.cache.BuiltInCacheDefinition;
import net.foxopen.fox.cache.CacheManager;
import net.foxopen.fox.cache.FoxCache;
import net.foxopen.fox.configuration.resourcemaster.AppResourceQueryDefinitions;
import net.foxopen.fox.configuration.resourcemaster.definition.AppProperty;
import net.foxopen.fox.configuration.resourcemaster.definition.FoxApplicationDefinition;
import net.foxopen.fox.configuration.resourcemaster.model.FileProperties;
import net.foxopen.fox.configuration.resourcemaster.model.FoxEnvironment;
import net.foxopen.fox.configuration.resourcemaster.model.SecurityProperties;
import net.foxopen.fox.database.ConnectionAgent;
import net.foxopen.fox.database.UCon;
import net.foxopen.fox.database.UConBindMap;
import net.foxopen.fox.database.UConStatementResult;
import net.foxopen.fox.database.parser.ParsedStatement;
import net.foxopen.fox.dom.DOM;
import net.foxopen.fox.dom.DOMList;
import net.foxopen.fox.enginestatus.EngineStatus;
import net.foxopen.fox.entrypoint.ComponentManager;
import net.foxopen.fox.entrypoint.FoxGlobals;
import net.foxopen.fox.ex.ExApp;
import net.foxopen.fox.ex.ExDB;
import net.foxopen.fox.ex.ExDBTimeout;
import net.foxopen.fox.ex.ExDBTooFew;
import net.foxopen.fox.ex.ExDBTooMany;
import net.foxopen.fox.ex.ExFoxConfiguration;
import net.foxopen.fox.ex.ExInternal;
import net.foxopen.fox.ex.ExModule;
import net.foxopen.fox.ex.ExServiceUnavailable;
import net.foxopen.fox.ex.ExUserRequest;
import net.foxopen.fox.filetransfer.VirusScanner;
import net.foxopen.fox.image.ImageWidgetProcessing;
import net.foxopen.fox.logging.FoxLogger;
import net.foxopen.fox.module.Mod;
import net.foxopen.fox.module.entrytheme.EntryTheme;
import net.foxopen.fox.module.fieldset.transformer.html.HTMLWidgetConfig;
import net.foxopen.fox.module.serialiser.HtmlDoctype;
import net.foxopen.fox.spatial.SpatialEngine;
import net.foxopen.fox.spell.Dictionary;
import net.foxopen.fox.sql.SQLManager;
import net.foxopen.fox.track.Track;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class App {

  // Static variables
  public static final String BOOTSTRAP_USER_CHANGE_IGNORE = "IGNORE";
  public static final String BOOTSTRAP_USER_CHANGE_TIMEOUT = "TIMEOUT";
  public static final Object TRUESIZE_LOCK = new Object();
  private static final String VIRUS_TYPE_ELEMENT_NAME = "type";
  private static final String VIRUS_IGNORE_TYPE = "IGNORE";

  static {
    EngineStatus.instance().registerStatusProvider(new AppStatusProvider());
  }

  private final Date mCreatedDate = new Date();

  // Member variables
  private final String mAppMnem;
  private final List<String> mAppAliasList;
  private final Map<String, String> mAppDisplayAttributeList = new HashMap<>();
  private final List<String> mResourceTableList;
  private final int mAjaxPollFrequency;
  private final Map<String, VirusScanner> mVirusScannerMap;
  private final String mDefaultHTMLWidgetConfigName;
  private final Map<String, HTMLWidgetConfig> mHTMLWidgetConfigMap = HTMLWidgetConfig.engineDefaults();

  private final HtmlDoctype mDefaultDocumentType;
  private final String mConnectionPoolName;

  // Dictionary Properties
  private final Dictionary mDefaultDictionary;
  private final Dictionary mDictionary;

  // File properties
  private final FileProperties mFileProperties;

  // Image Properties
  private final ImageWidgetProcessing mImageWidgetProcessing;

  // Security Properties
  private final SecurityProperties mSecurityProperties;

  // Module Properties
  private final String mDefaultModuleName;
  private final String mTimeoutModuleName;
  private final String mSecurityCheckModuleName;

  private final String mErrorComponentName;
  private final String mResponseMethod;

  // Environment Properties
  private final String mLogoutPage;
  private final String mExitPage;

  // Processing variables
  private final SpatialEngine mSpatialEngine;

  private final AppResourceQueryDefinitions mResourceQueryDefinitions;

  /** Set of all components listed for this App.
   * Used to find out if a component can be found in this app without a db lookup each request.
   * Populated JIT
   */
  private Set<String> mComponentNameSet;

  /**
   * Create an App performing any set up processing required on it.
   *
   * @param pAppDefinition
   */
  public static App createApp(String pAppMnem, FoxApplicationDefinition pAppDefinition, FoxEnvironment pFoxEnvironment)
  throws ExServiceUnavailable, ExApp, ExFoxConfiguration {
    App lApp = new App(pAppMnem, pAppDefinition, pFoxEnvironment);
    return lApp;
  }

  private Dictionary createDictionary(DOM lDictionaryDOM) throws ExApp {
    if (lDictionaryDOM != null) {
      String lDictionaryName = lDictionaryDOM.getAttr("name");
      List<String> lDictionaryList = loadDomListValuesIntoStringList(lDictionaryDOM, "*");
      TreeSet<String> lDictionaryTreeSet = new TreeSet<String>();
      for (String lCurrentDictionary : lDictionaryList) {
        lDictionaryTreeSet.add(lCurrentDictionary);
      }
      return Dictionary.getOrCreateDictionary(lDictionaryName, lDictionaryTreeSet);
    }
    else {
      return null;
    }
  }

  /**
   * Construct the App object from the XML validating it first.
   *
   * @throws ExServiceUnavailable
   * @throws ExApp
   * @throws ExInternal
   */
  private App(String pAppMnem, FoxApplicationDefinition pAppDefinition, FoxEnvironment pFoxEnvironment)
  throws ExServiceUnavailable, ExApp, ExInternal, ExFoxConfiguration {
    // Load application speicifc properties
    mAppMnem = pAppMnem;

    List<String> lAppAliasList = new ArrayList<>();
    DOM lAppAliasDOM = pAppDefinition.getPropertyAsDOM(AppProperty.APP_ALIAS_LIST);
    if (lAppAliasDOM != null) {
      DOMList lAppAliasDOMList = lAppAliasDOM.getUL("*");
      for (int i = 0; i < lAppAliasDOMList.getLength(); i ++) {
        DOM lAppAliasItem = lAppAliasDOMList.item(i);
        lAppAliasList.add(lAppAliasItem.value());
      }
      mAppAliasList = Collections.unmodifiableList(lAppAliasList);
    }
    else {
      mAppAliasList = Collections.emptyList();
    }

    DOM lResourceTableDOMList = pAppDefinition.getPropertyAsDOM(AppProperty.RESOURCE_TABLE_LIST);
    mResourceTableList = App.loadDomListValuesIntoStringList(lResourceTableDOMList, "*");

    mAjaxPollFrequency = Integer.parseInt(pAppDefinition.getPropertyAsString(AppProperty.AJAX_POLL_FREQUENCY));

    DOM lVirusScannerListDOM = pAppDefinition.getPropertyAsDOM(AppProperty.VIRUS_SCANNER_LIST);
    mVirusScannerMap = processVirusScannerList(lVirusScannerListDOM);

    mDictionary = createDictionary(pAppDefinition.getPropertyAsDOM(AppProperty.DICTIONARY_LIST));
    mDefaultDictionary = createDictionary(pAppDefinition.getPropertyAsDOM(AppProperty.DICTIONARY_LIST));

    mDefaultDocumentType = HtmlDoctype.getByNameOrNull(pAppDefinition.getPropertyAsString(AppProperty.DEFAULT_HTML_DOCTYPE));

    // See if the connect key has been specified on the resource master, if not get it from the database connections table.
    String lPoolName = pAppDefinition.getPropertyAsString(AppProperty.CONNECTION_POOL_NAME);
    if (!XFUtil.isNull(lPoolName)) {
      mConnectionPoolName = lPoolName;
    }
    else {
      mConnectionPoolName = getConnectKeyFromDatabase(FoxGlobals.getInstance().getFoxBootConfig().getFoxEnvironmentKey(), FoxGlobals.getInstance().getEngineLocator());
    }

    // Test pool name
    if(!ConnectionAgent.checkConnectionExists(mConnectionPoolName)) {
      throw new ExApp("Connection pool '" + mConnectionPoolName + "' is not defined in this engine's connection agent");
    }

    mFileProperties = FileProperties.createFileProperties(pAppMnem, pAppDefinition, pFoxEnvironment, this);

    mImageWidgetProcessing = ImageWidgetProcessing.createImageWidgetProcessing(pAppDefinition);

    mSecurityProperties = SecurityProperties.createSecurityProperties(pAppDefinition);

    // Module properties
    mDefaultModuleName = pAppDefinition.getPropertyAsString(AppProperty.MODULE_DEFAULT_MODULE);
    mTimeoutModuleName = pAppDefinition.getPropertyAsString(AppProperty.MODULE_TIMEOUT_MODULE);
    mSecurityCheckModuleName = pAppDefinition.getPropertyAsString(AppProperty.MODULE_SECURITY_CHECK_MODULE);

    mErrorComponentName = pAppDefinition.getPropertyAsString(AppProperty.ERROR_COMPONENT_NAME);

    mResponseMethod = pAppDefinition.getPropertyAsString(AppProperty.RESPONSE_METHOD);

    mDefaultHTMLWidgetConfigName = pAppDefinition.getPropertyAsString(AppProperty.HTML_WIDGET_CONFIG);

    // Environment properties
    mExitPage = pAppDefinition.getPropertyAsString(AppProperty.EXIT_PAGE);
    if (XFUtil.isNull(mExitPage)) {
      throw new ExApp("App " + mAppMnem + " cannot be created with no exit-page set (property path " + AppProperty.EXIT_PAGE.getPath() + ")");
    }

    mLogoutPage = pAppDefinition.getPropertyAsString(AppProperty.LOGOUT_PAGE);

    // Load DOM properties
    DOM lDisplayAttrDOM = pAppDefinition.getPropertyAsDOM(AppProperty.APP_DISPLAY_ATTR_LIST);
    if (lDisplayAttrDOM != null) {
      loadDomPropertyIntoStringMap(lDisplayAttrDOM, mAppDisplayAttributeList, AppProperty.APP_DISPLAY_ATTR_LIST.getPath() + "/*", "name"); // make final
    }

    mResourceQueryDefinitions = new AppResourceQueryDefinitions(mAppMnem, mResourceTableList);

    //Generate list of component names in the database
    mComponentNameSet = null; //This gets populated JIT

    // Process spatial engine
    String lSpatialEngine = pAppDefinition.getPropertyAsString(AppProperty.SPATIAL_WMS_URL);
    if (!XFUtil.isNull(lSpatialEngine)) {
      mSpatialEngine = new SpatialEngine(this, lSpatialEngine);
    } else {
      mSpatialEngine = null;
    }
  }

  public Dictionary getDefaultDictionary() {
    return mDefaultDictionary;
  }

  public Dictionary getDictionary() {
    return mDictionary;
  }

  private String getConnectKeyFromDatabase(String pFoxEnvironment, String pEngineLocator) throws ExApp {
    ContextUCon lContextUCon = null;
    try {
      lContextUCon = ContextUCon.createContextUCon(FoxGlobals.getInstance().getEngineConnectionPoolName(), "Main Fox ContextUCon");
      lContextUCon.pushConnection("Get App Connect Key");
      UCon lConnectKeyUCon = null;
      try {
        lConnectKeyUCon = lContextUCon.getUCon("Get the connect key");

        UConBindMap lBindMap = new UConBindMap();
        lBindMap.defineBind(":p_environment_key", pFoxEnvironment);
        lBindMap.defineBind(":p_engine_locator", pEngineLocator);

        String lConnectKey = "";

        try {
          lConnectKey = lConnectKeyUCon.queryScalarString(SQLManager.instance().getStatement("GetConnectKey.sql", App.class), lBindMap);
        }
        catch (ExDBTooMany | ExDBTooFew | ExDBTimeout e) {
          throw new ExApp("Could not get the default connect key for the fox environment '" + pFoxEnvironment + "' and engine '" + pEngineLocator + "'", e);
        }
        catch (ExDB e) {
          throw new ExApp("Could not get the default connect key for the fox environment '" + pFoxEnvironment + "' and engine '" + pEngineLocator + "'", e);
        }

        if (XFUtil.isNull(lConnectKey)) {
          throw new ExApp("Could not get the default connect key for the fox environment '" + pFoxEnvironment + "' and engine '" + pEngineLocator + "'");
        }
        else {
          return lConnectKey;
        }
      } finally {
        if (lConnectKeyUCon != null) {
          lContextUCon.returnUCon(lConnectKeyUCon, "Get the connect key");
        }
      }
    } finally {
      if (lContextUCon != null) {
        lContextUCon.popConnection("Get App Connect Key");
      }
    }
  }

  public String getConnectionPoolName() {
    return mConnectionPoolName;
  }

  public HtmlDoctype getDefaultDocumentType() {
    return mDefaultDocumentType;
  }

  public int getPreSessionTimeoutPromptSecs() {
    return mSecurityProperties.getPreSessionTimeoutPromptSecs();
  }

  public boolean isSecureCookies() {
    return mSecurityProperties.isSecureCookies();
  }


  /**
   * If pConfigName is null or empty, the app's default is returned.
   * @param pConfigName
   * @return
   */
  public HTMLWidgetConfig getHTMLWidgetConfig(String pConfigName) {
    return mHTMLWidgetConfigMap.get(XFUtil.nvl(pConfigName, mDefaultHTMLWidgetConfigName));
  }

  public List<String> getAppAliasList() {
    return mAppAliasList;
  }

  public String getAppMnem() {
    return mAppMnem;
  }

  public Map<String, String> getAppDisplayAttributeList() {
    return mAppDisplayAttributeList;
  }

  // Presumes attribute is the key and the text node is value
  private static void loadDomPropertyIntoStringMap(DOM pDOMProperty, Map<String, String> pMemberMapToLoad, String pListPath, String pNameAttribute) throws ExApp {
    DOMList lDomPropertyList = pDOMProperty.getUL(pListPath);

    if (lDomPropertyList == null) {
      throw new ExApp("A property list was found to be null for list path and attribute: " + pListPath + " , " + pNameAttribute);
    }

    for (int i = 0; i < lDomPropertyList.getLength(); i++) {
      DOM lDisplayAttr = lDomPropertyList.item(i);
      String lName = lDisplayAttr.getAttr(pNameAttribute);
      String lValue = lDisplayAttr.value();
      pMemberMapToLoad.put(lName, lValue);
    }
  }

  private static List<String> loadDomListValuesIntoStringList (DOM pDOMProperty, String pListPath) throws ExApp {
    DOMList lPropertyDOMList = pDOMProperty.getUL(pListPath);

    if (lPropertyDOMList == null) {
      throw new ExApp("A property DOM list was found to be null when loading values for this path : " + pListPath);
    }
    else if (lPropertyDOMList.getLength() < 1) {
      throw new ExApp("A property DOM list was found to have no elements when loading values for this path : " + pListPath);
    }

    List<String> lPropertyStringList = new ArrayList<>();

    for (int i = 0; i < lPropertyDOMList.getLength(); i++) {
      String lPropertyValue = lPropertyDOMList.item(i).value();

      if (XFUtil.isNull(lPropertyValue)) {
        throw new ExApp("A property DOM value was found to be null with path : " + pListPath);
      }

      lPropertyStringList.add(lPropertyValue);
    }

    return lPropertyStringList;
  }

  private Map<String, VirusScanner> processVirusScannerList(DOM pVirusScannerList) throws ExApp {
    Map<String, VirusScanner> lVirusScannerList = new HashMap<>();
    DOMList lVirusScannerDOMList = pVirusScannerList.getUL("*");
    for (int i = 0; i < lVirusScannerDOMList.getLength(); i++) {
      DOM lVirusScannerDOM = lVirusScannerDOMList.item(i);
      String lType = lVirusScannerDOM.get1SNoEx(VIRUS_TYPE_ELEMENT_NAME);
      if (!VIRUS_IGNORE_TYPE.equals(lType)) {
        VirusScanner lVirusScanner = VirusScanner.createVirusScanner(lVirusScannerDOM);
        lVirusScannerList.put(lVirusScanner.getName(), lVirusScanner);
      }
    }
    return lVirusScannerList;
  }

  // TODO AT Refactor this into getAppMnem.
  public String getMnemonicName() {
    return mAppMnem;
  }

  public List<String> getResourceTableList() {
    return mResourceTableList;
  }

  public int getAjaxPollFrequency() {
    return mAjaxPollFrequency;
  }

  public FileProperties getFileProperties() {
    return mFileProperties;
  }

  public String getDefaultFileUploadType() {
    return mFileProperties.getDefaultFileUploadType();
  }

  public String getDefaultModuleName() {
    return mDefaultModuleName;
  }

  public String getErrorComponentName() {
    return mErrorComponentName;
  }

  public ParsedStatement getResourceTableParsedStatement() {
    return mResourceQueryDefinitions.getResourceTableParsedStatement();
  }

  private ComponentImage getImage(String pImageName)
  throws ExInternal, ExServiceUnavailable, ExModule, ExApp
  {
    // Get image component and check it's an image-type
    try {
      FoxComponent lFoxComponent = ComponentManager.getComponent(pImageName, getAppMnem());

      if(!(lFoxComponent instanceof ComponentImage)) {
        throw new ExModule("Component '"+pImageName+"' is not correct data type (image)");
      }

      return (ComponentImage)lFoxComponent;
    }
    catch(ExUserRequest x) {
      throw new ExModule("Error loading image '"+pImageName+"'", x);
    }
  }

  public boolean isEntryThemeSecurityOn() {
    return mSecurityProperties.isExternalEntryThemeSecurity();
  }

  /**
   * Should module responses for this app be streamed out to the user, or should the output get buffered before sending.
   * Streaming output should be on by default for FOX5 but can be turned off if problems arise in production.
   *
   * @return ResponseMethod to use when responding to a module request
   */
  public ResponseMethod getResponseMethod() {
    return ResponseMethod.fromExternalString(XFUtil.nvl(mResponseMethod, "streaming"));
  }

  public String getExitPage() {
    return mExitPage;
  }

  public String getLogoutPage() {
    return mLogoutPage;
  }

  /**
   * Gets the fileUploadType of given name, or the app default if name is null.
   * @param pTypeName
   * @return
   */
  public FileUploadType getFileUploadType(String pTypeName) {
    String lTypeName;
    if(XFUtil.isNull(pTypeName)){
      if(XFUtil.isNull(mFileProperties.getDefaultFileUploadType())){
        throw new ExInternal("Error getting FileUploadType. " + mAppMnem +  " does not have a default FileUploadType defined. Either explicitly specify a type or update the resource master definition.");
      }
      lTypeName = mFileProperties.getDefaultFileUploadType();
    }
    else {
      lTypeName = pTypeName;
    }

    FileUploadType lFileUploadType = mFileProperties.getFileUploadTypeMap().get(lTypeName);
    if(lFileUploadType == null){
      throw new ExInternal("Could not locate FileUploadType of name " + pTypeName + " in app "+ mAppMnem);
    }
    return lFileUploadType;
  }

  private final void populateComponentNameSet (UCon pLoadUCon) throws ExApp {
    try {
      List<UConStatementResult> lComponentNames = pLoadUCon.queryMultipleRows(mResourceQueryDefinitions.getResourceNamesParsedStatement());
      Set<String> lComponentNameSet = new HashSet<>(lComponentNames.size());
      for (UConStatementResult lRow : lComponentNames) {
        lComponentNameSet.add(lRow.getString("NAME"));
      }
      mComponentNameSet = lComponentNameSet;
    }
    catch (Throwable ex) {
      throw new ExApp("Failed to populate component names list", ex);
    }
  }

  /**
   * Get a component from the cache, if it exists, using the component name
   *
   * @param pComponentName Component name
   * @return
   * @throws ExModule
   * @throws ExServiceUnavailable
   * @throws ExApp
   */
  private final FoxComponent getComponentFromCache(String pComponentName) {
    //Check app specific cache
    FoxCache<String, FoxComponent> lFoxCache = CacheManager.getMemberCache(BuiltInCacheDefinition.APP_COMPONENTS, mAppMnem);
    FoxComponent lFoxComponent = lFoxCache.get(pComponentName);

    if (lFoxComponent != null) {
      return lFoxComponent;
    }
    else {
      return null;
    }
  }

  /**
   * Get a component row from the apps resource tables, if it exists, using the component name
   *
   * @param pComponentName
   * @param pLoadUCon
   * @return
   * @throws ExServiceUnavailable thrown if there was a database error
   */
  private final UConStatementResult getComponentRowFromResourceTables(String pComponentName, final UCon pLoadUCon)
  throws ExServiceUnavailable {
    try {
      return pLoadUCon.querySingleRow(mResourceQueryDefinitions.getResourceTableParsedStatement(), pComponentName);
    }
    catch(ExDBTooFew x) {
      return null;
    }
    catch(ExDB x) {
      throw x.toServiceUnavailable();
    }
  }

  /**
   * Construct a FoxComponent object from a connection statement result row queried off a FOX resource table
   *
   * @param pRow
   * @return
   * @throws ExModule
   * @throws ExServiceUnavailable
   * @throws ExApp
   */
  private final FoxComponent createFoxComponentFromRow(UConStatementResult pRow)
  throws ExModule, ExServiceUnavailable, ExApp {
    // Load application component
    Clob lClob = pRow.getClob("DATA");
    Blob lBlob = pRow.getBlob("BINDATA");
    String lName = pRow.getString("NAME");
    Reader lReader = null;
    InputStream lInputStream = null;
    try {
      if(lClob != null) {
        lReader = lClob.getCharacterStream();
      }
      if(lBlob != null) {
        lInputStream = lBlob.getBinaryStream();
      }

      return FoxComponent.createComponent(
        lName
      , pRow.getString("TYPE")
      , lReader
      , lInputStream
      , this // owning application
        , (pRow.columnExists("BROWSER_CACHE_MS") ? pRow.getLong("BROWSER_CACHE_MS") : ComponentManager.getComponentBrowserCacheMS())
      );
    }
    catch(SQLException x) {
      throw new ExInternal("Error reading blob/clob", x);
    }
    finally {
      try { if(lReader != null) lReader.close(); } catch(IOException x) {}
      try { if(lInputStream != null) lInputStream.close(); } catch(IOException x) {}
    }
  }

  /**
   * Go through the list of known components this App has and check various parts of the pComponentPath against that set
   *
   * @param pComponentPath Component Path passed in
   * @return Component name that matches a component for this App
   */
  private String getComponentName(String pComponentPath) {
    StringBuilder lComponentPath = new StringBuilder(pComponentPath);
    StringBuilder lPoppedComponentParts = new StringBuilder();
    while(lComponentPath.length() > 0) {
      if(mComponentNameSet != null && mComponentNameSet.contains(lComponentPath.toString())) {
        return lComponentPath.toString();
      }

      XFUtil.pathPushHead(lPoppedComponentParts, XFUtil.pathPopTail(lComponentPath));
    }

    return null;
  }

  public final FoxComponent getComponent(String pComponentPath, boolean pUseCache)
  throws
    ExServiceUnavailable
  , ExModule // when module fails to validate
  , ExApp // when app resource file type not known
  , ExUserRequest // when URL pathname invalid
  , ExInternal {
    final String UCON_PURPOSE = "App.getComponment";
    FoxComponent lFoxComponent = null;

    // Use default module if none specified
    String lFullComponentPath = pComponentPath;
    if(XFUtil.isNull(pComponentPath)) {
      lFullComponentPath = mDefaultModuleName;
    }

    UCon lUCon = null;
    Track.pushInfo("GetComponent");
    try {
      // If the component name set is empty, make sure it's populated JIT
      if (mComponentNameSet == null || mComponentNameSet.size() == 0) {
        lUCon = ConnectionAgent.getConnection(mConnectionPoolName, UCON_PURPOSE);
        populateComponentNameSet(lUCon);
      }

      UConStatementResult lRow = null;

      // Attempt to find a valid component name from the component path
      String lComponentName = getComponentName(lFullComponentPath);
      // If lComponentName is not null then it was found in the app component name set
      if (!XFUtil.isNull(lComponentName)) {

        if (pUseCache) {
          // Check the cache and return if found
          lFoxComponent = getComponentFromCache(lComponentName);
          if (lFoxComponent != null) {
            return lFoxComponent;
          }
        }

        // Get a connection if we didn't already ask for one above
        if(lUCon == null) {
          lUCon = ConnectionAgent.getConnection(mConnectionPoolName, "Get component");
        }
        //Check components tables
        lRow = getComponentRowFromResourceTables(lComponentName, lUCon);
      }

      if (lRow != null) {
        lFoxComponent = createFoxComponentFromRow(lRow);

        if(pUseCache) {
          //Put it in the app specific cache if it wasn't there already
          FoxCache<String, FoxComponent> lFoxCache = CacheManager.getMemberCache(BuiltInCacheDefinition.APP_COMPONENTS, mAppMnem);
          lFoxCache.put(lRow.getString("NAME"), lFoxComponent);

          //Put in engine mirror too if it should be
          if ("Y".equals(lRow.getString("ENGINE_MIRROR"))) {
            try {
              if (!lRow.isNull("DATA")) {
                EngineMirror.storeFile(mAppMnem, lRow.getString("NAME"), lRow.getClob("DATA").getCharacterStream(), lRow.getString("TYPE"));
              }
              else {
                EngineMirror.storeFile(mAppMnem, lRow.getString("NAME"), lRow.getBlob("BINDATA").getBinaryStream(), lRow.getString("TYPE"));
              }
            }
            catch (Exception e) {
              FoxLogger.getLogger().error("App getComponent failed to store component in EngineMirror", e);
            }
          }
        }
      }
    }
    finally {
      if(lUCon != null) {
        lUCon.closeForRecycle();
      }
      Track.pop("GetComponent");
    }

    if(lFoxComponent != null) {
      return lFoxComponent;
    }

    //If it's not found anything and gets to here, throw an error
    throw new ExUserRequest("Service '" + pComponentPath + "' not known, please check URL.");
  }

  /**
   * Get a component specific to this App and throw ExInternal if the resulting component is not a Module
   *
   * @param pModuleURL
   * @return
   * @throws ExServiceUnavailable
   * @throws ExModule
   * @throws ExApp
   * @throws ExUserRequest
   * @throws ExInternal
   */
  public final Mod getMod(String pModuleURL)
  throws
    ExServiceUnavailable
  , ExModule // when module fails to validate
  , ExApp // when app resource file type not known
  , ExUserRequest // when URL pathname invalid
  , ExInternal
  {
    FoxComponent lFoxComponent = getComponent(pModuleURL, true);
    if(!(lFoxComponent instanceof Mod)) {
      throw new ExInternal("Service " + lFoxComponent.getName() + " not Fox Module type.");
    }
    return (Mod)lFoxComponent;
  }

  public String getMapSetTableName() {
    //TODO PN this should be configurable
    return "envmgr.env_mapsets_xml";
  }

  public final Mod getTimeoutMod() {
    if (XFUtil.isNull(mTimeoutModuleName)) {
      throw new ExInternal("Failed to locate timeout module for App '" + mAppMnem + "'");
    }

    Mod lTimeoutMod = null;
    try{
      lTimeoutMod = getMod(mTimeoutModuleName);
    }
    catch (Throwable e){
      throw new ExInternal("Failed to parse timeout module for App '" + mAppMnem + "' (module name = '" + mTimeoutModuleName +  "')");
    }
    return lTimeoutMod;
  }

  /**
   * Gets the entry theme to which a user should be directed in the event that they fail a privilege check.
   * @return
   */
  public EntryTheme getSecurityCheckEntryTheme() {
    if (XFUtil.isNull(mSecurityCheckModuleName)) {
      throw new ExInternal("Failed to locate security check module for App '" + mAppMnem + "'");
    }

    //TODO PN - improve this so entry theme can be specified in the property
    try {
      return getMod(mSecurityCheckModuleName).getEntryTheme("new");
    }
    catch (Throwable th) {
      throw new ExInternal("Failed to parse security check module for App '" + mAppMnem + "' (module name = '" + mSecurityCheckModuleName +  "')");
    }
  }

  public ImageWidgetProcessing getImageWidgetProcessing() {
    return mImageWidgetProcessing;
  }

  public Set<String> getComponentNameSet() {
    return Collections.unmodifiableSet(mComponentNameSet == null ? Collections.<String>emptySet() : mComponentNameSet);
  }

  public Map<String, VirusScanner> getVirusScannerMap() {
    return mVirusScannerMap;
  }

  public SpatialEngine getSpatialEngine() {
    return mSpatialEngine;
  }

  Date getObjectCreatedDate() {
    return mCreatedDate;
  }

  String getTimeoutModuleName() {
    return mTimeoutModuleName;
  }

  String getSecurityCheckModuleName() {
    return mSecurityCheckModuleName;
  }
}
