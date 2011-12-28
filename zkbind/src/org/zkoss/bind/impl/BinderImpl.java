/* BinderImpl.java

	Purpose:
		
	Description:
		
	History:
		Jul 29, 2011 6:08:51 PM, Created by henrichen

Copyright (C) 2011 Potix Corporation. All Rights Reserved.
*/

package org.zkoss.bind.impl;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.zkoss.bind.BindComposer;
import org.zkoss.bind.BindContext;
import org.zkoss.bind.Binder;
import org.zkoss.bind.Converter;
import org.zkoss.bind.Form;
import org.zkoss.bind.FormExt;
import org.zkoss.bind.Phase;
import org.zkoss.bind.PhaseListener;
import org.zkoss.bind.Property;
import org.zkoss.bind.SimpleForm;
import org.zkoss.bind.Validator;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.converter.FormatedDateConverter;
import org.zkoss.bind.converter.FormatedNumberConverter;
import org.zkoss.bind.converter.ObjectBooleanConverter;
import org.zkoss.bind.converter.UriConverter;
import org.zkoss.bind.sys.BindEvaluatorX;
import org.zkoss.bind.sys.BinderCtrl;
import org.zkoss.bind.sys.Binding;
import org.zkoss.bind.sys.CommandBinding;
import org.zkoss.bind.sys.ConditionType;
import org.zkoss.bind.sys.FormBinding;
import org.zkoss.bind.sys.ValidationMessages;
import org.zkoss.bind.sys.LoadBinding;
import org.zkoss.bind.sys.PropertyBinding;
import org.zkoss.bind.sys.SaveBinding;
import org.zkoss.bind.sys.SaveFormBinding;
import org.zkoss.bind.sys.SavePropertyBinding;
import org.zkoss.bind.sys.tracker.Tracker;
import org.zkoss.bind.tracker.impl.TrackerImpl;
import org.zkoss.bind.xel.zel.BindELContext;
import org.zkoss.lang.Classes;
import org.zkoss.lang.Strings;
import org.zkoss.lang.reflect.Fields;
import org.zkoss.util.CacheMap;
import org.zkoss.util.logging.Log;
import org.zkoss.xel.ExpressionX;
import org.zkoss.zk.ui.AbstractComponent;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Execution;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.UiException;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.event.EventListener;
import org.zkoss.zk.ui.event.EventQueue;
import org.zkoss.zk.ui.event.EventQueues;
import org.zkoss.zk.ui.event.Events;
import org.zkoss.zk.ui.metainfo.Annotation;
import org.zkoss.zk.ui.sys.ComponentCtrl;
import org.zkoss.zk.ui.util.Composer;
import org.zkoss.zk.ui.util.Template;

/**
 * Implementation of Binder.
 * @author henrichen
 * @author dennischen
 *
 */
public class BinderImpl implements Binder,BinderCtrl,Serializable {

	private static final long serialVersionUID = 1463169907348730644L;

	private static final Log _log = Log.lookup(BinderImpl.class);
	
	private static final Map<String, Converter> CONVERTERS = new HashMap<String, Converter>();
	private static final Map<String, Validator> VALIDATORS = new HashMap<String, Validator>();
	private static final Map<String, Object> RENDERERS = new HashMap<String, Object>();
	static {
		initConverter();
		initValidator();
	}

	//TODO can be defined in property-library
	private static void initConverter() {
		//TODO use library-property to initialize default user converters
		CONVERTERS.put("objectBoolean", new ObjectBooleanConverter());
		CONVERTERS.put("formatedDate", new FormatedDateConverter());
		CONVERTERS.put("formatedNumber", new FormatedNumberConverter());
		
		CONVERTERS.put("uri", new UriConverter());
	}
	
	//TODO can be defined in property-library
	private static void initValidator() {
		//TODO initial the system validator
		
	}
	
	//control keys
	public static final String BINDING = "$BINDING$"; //a binding
	public static final String BINDER = "$BINDER$"; //the binder
	public static final String BINDCTX = "$BINDCTX$"; //bind context
	public static final String VAR = "$VAR$"; //variable name in a collection
	public static final String ITERATION_VAR = "$INTERATION_VAR$"; //iteration status variable name in a collection
	public static final String VM = "$VM$"; //the associated view model
	public static final String QUE = "$QUE$"; //the associated event queue name
	public static final String NOTIFYS = "$NOTIFYS$"; //changed properties to be notified
	public static final String VALIDATES = "$VALIDATES$"; //properties to be validated
	public static final String SRCPATH = "$SRCPATH$"; //source path that trigger @DependsOn tracking
	
	public static final String IGNORE_TRACKER = "$IGNORE_TRACKER$"; //ignore adding currently binding to tracker, ex in init
	public static final String SAVE_BASE = "$SAVE_BASE$"; //bean base of a save operation
	public static final String ON_BIND_INIT = "onBindInit"; //do component binding initialization
	
	
	//System Annotation, see lang-addon.xml
	private static final String SYSBIND = "$SYSBIND$"; //system binding annotation name
	private static final String RENDERER = "$R$"; //system renderer for binding
	private static final String LOADEVENT = "$LE$"; //load trigger event
	private static final String SAVEEVENT = "$SE$"; //save trigger event
	private static final String ACCESS = "$A$"; //access type (load|save|both), load is default
	private static final String CONVERTER = "$C$"; //system converter for binding
	private static final String VALIDATOR = "$V$"; //system validator for binding
	
	private static final String LOAD_REPLACEMENT = "$LR$"; //loadreplacement of attribute
	private static final String LOAD_TYPE = "$LT$"; //expected type of attribute
	
	private static final String ON_POST_COMMAND = "onPostCommand";
	
	//private control key
	private static final String FORM_ID = "$FORM_ID$";
	
	//Command lifecycle result
	private static final int SUCCESS = 0;
	private static final int FAIL_VALIDATE = 1;
	
	
	//TODO make it configurable
	private final static Map<Class<?>, List<Method>> _initMethodCache = 
		new CacheMap<Class<?>, List<Method>>(1000,CacheMap.DEFAULT_LIFETIME); //class,list<init method>
	
	private final static Map<Class<?>, Map<String,Box<Method>>> _commandMethodCache = 
		new CacheMap<Class<?>, Map<String,Box<Method>>>(1000,CacheMap.DEFAULT_LIFETIME); //class,map<command, null-able command method>
	
	private Component _rootComp;
	private BindEvaluatorX _eval;
	private PhaseListener _phaseListener;
	private Tracker _tracker;
	private final Component _dummyTarget = new AbstractComponent();//a dummy target for post command
	
	/* holds all binding in this binder */
	private final Map<Component, Map<String, List<Binding>>> _bindings; //comp -> (evtnm | _fieldExpr | formid) -> bindings

	private final FormBindingHandler _formBindingHandler;
	private final PropertyBindingHandler _propertyBindingHandler;
	
	/* the relation of form and inner save-bindings */
	private Map<Component, Set<SaveBinding>> _assocFormSaveBindings;//form comp -> savebindings	
	private Map<Component, Map<SaveBinding,Set<SaveBinding>>> _reversedAssocFormSaveBindings;////associated comp -> binding -> associated save bindings of _formSaveBindingMap
	

	private final Map<BindingKey, CommandEventListener> _listenerMap; //comp+evtnm -> eventlistener
	private final String _quename;
	private final String _quescope;
	private final EventListener<Event> _queueListener;
	
	private ValidationMessages _validationMessages;
	private Set<BindingKey> _hasValidators;//the key to mark they have validator
	
	//flag to keep info of current vm has converter method or not
	private boolean _hasGetConverterMethod = true;
	
	//flag to keep info of current vm has validator method or not
	private boolean _hasGetValidatorMethod = true;
	
	private boolean _init = false;
	
	public BinderImpl() {
		this(null,null);
	}
	
	public BinderImpl(String qname, String qscope) {
		_bindings = new HashMap<Component, Map<String, List<Binding>>>();
		_formBindingHandler = new FormBindingHandler(this); 
		_propertyBindingHandler = new PropertyBindingHandler(this);
		
		_assocFormSaveBindings = new HashMap<Component, Set<SaveBinding>>();
		_reversedAssocFormSaveBindings = new HashMap<Component, Map<SaveBinding,Set<SaveBinding>>>();
		
		_hasValidators = new HashSet<BindingKey>();
		
		_listenerMap = new HashMap<BindingKey, CommandEventListener>();
		//use same queue name if user was not specified, 
		//this means, binder in same scope, same queue, they will share the notification by "base"."property" 
		_quename = qname != null && !Strings.isEmpty(qname) ? qname : BinderImpl.QUE;
		_quescope = qscope != null && !Strings.isBlank(qscope) ? qscope : EventQueues.DESKTOP;
		_queueListener = new EventListener<Event>() {
			public void onEvent(Event event) throws Exception {
				//only when a event in queue is our event
				if(event instanceof PropertyChangeEvent){
					final PropertyChangeEvent evt = (PropertyChangeEvent) event;
					BinderImpl.this.loadOnPropertyChange(evt.getBase(), evt.getPropertyName());
				}
			}
		};
	}
	
	private void checkInit(){
		if(!_init){
			throw new UiException("binder is not initialized yet");
		}
	}
	
	public void init(Component comp, Object vm){
		if(_init) throw new UiException("binder is already initialized");
		_init = true;
		
		_rootComp = comp;
		//initial associated view model
		setViewModel(vm);
		_dummyTarget.addEventListener(ON_POST_COMMAND, new PostCommandListener());
		//subscribe change listener
		subscribeChangeListener(_quename, _quescope, _queueListener);
		
		if(vm instanceof Composer<?> && !(vm instanceof BindComposer<?>)){//do we need to warn this?
			//show a warn only
			_log.warning("you are using a composer [%s] as a view model",vm);
		}
		//Should we handle here or in setViewModel for every time set a view model into binder?
		initViewModel(vm);
	}
	
	//handle init of a viewmodel. 
	private void initViewModel(Object viewModel){
		final Class<?> clz = viewModel.getClass();
		List<Method> inits = getInitMethods(clz);
		if(inits.size()==0) return;//no init method
		for(Method m : inits){
			final BindContext ctx = BindContextUtil.newBindContext(this, null, false, null, _rootComp, null);
			try {
				ParamCall parCall = createParamCall(ctx);
				parCall.call(viewModel, m);
			} catch (Exception e) {
				synchronized(_initMethodCache){//remove it for the hot deploy case if getting any error
					_initMethodCache.remove(clz);
				}
				throw new UiException(e.getMessage(),e);
			}
		}
	}
	
	private List<Method> getInitMethods(Class<?> clz) {
		List<Method> inits = _initMethodCache.get(clz);
		if(inits!=null) return inits;
		
		synchronized(_initMethodCache){
			inits = _initMethodCache.get(clz);//check again
			if(inits!=null) return inits;
			
			inits = new ArrayList<Method>(); //if still null in synchronized, scan it
			
			Class<?> curr = clz;
			
			while(curr!=null && !curr.equals(Object.class)){
				Method currm = null;
				Init init = null;
				//only allow one init method in a class.
				for(Method m : curr.getDeclaredMethods()){
					final Init i = m.getAnnotation(Init.class);
					if(i==null) continue;
					if(currm!=null){
						throw new UiException("more than one @Init method in calss "+curr+" first:"+currm+", secondary:"+m);
					}
					init = i;
					currm = m;
				}
				
				if(currm!=null){
					//super first
					inits.add(0,currm);
				}
				//check if we should take care super's init also.
				curr = (init!=null && init.upward())?curr.getSuperclass():null;
			}
			inits = Collections.unmodifiableList(inits);
			_initMethodCache.put(clz, inits);
		}
		return inits;
	}

	//called when onPropertyChange is fired to the subscribed event queue
	private void loadOnPropertyChange(Object base, String prop) {
		if(_log.debugable()){
			_log.debug("loadOnPropertyChange:base=[%s],prop=[%s]",base,prop);
		}
		final Tracker tracker = getTracker();
		final Set<LoadBinding> bindings = tracker.getLoadBindings(base, prop);
		for(LoadBinding binding : bindings) {
			final BindContext ctx = BindContextUtil.newBindContext(this, binding, false, null, binding.getComponent(), null);
			if(binding instanceof PropertyBinding){
				BindContextUtil.setConverterArgs(this, binding.getComponent(), ctx, (PropertyBinding)binding);
			}
			
			if(_log.debugable()){
				_log.debug("loadOnPropertyChange:binding.load(),binding=[%s],context=[%s]",binding,ctx);
			}
			binding.load(ctx);
			
			if(_validationMessages!=null){
				String attr = null;
				if(binding instanceof PropertyBinding){
					attr = ((PropertyBinding)binding).getFieldName();
				}else if(binding instanceof FormBinding){
					attr = ((FormBinding)binding).getFormId();
				}else{
					throw new UiException("unknow binding type "+binding); 
				}
				if(hasValidator(binding.getComponent(), attr)){
					_validationMessages.clearMessages(binding.getComponent(),attr);
				}
			}
		}
	}
	
	public void setViewModel(Object vm) {
		checkInit();
		_rootComp.setAttribute(BinderImpl.VM, vm);
		_hasGetConverterMethod = true;//reset to true
		_hasGetValidatorMethod = true;//reset to true
	}
	
	public Object getViewModel() {
		checkInit();
		return _rootComp.getAttribute(BinderImpl.VM);
	}
	
	//Note: assume system converter is state-less
	public Converter getConverter(String name) {
		checkInit();
		Converter converter = null;
		if(_hasGetConverterMethod){
			final BindEvaluatorX eval = getEvaluatorX();
			final ExpressionX vmc = eval.parseExpressionX(null, 
				new StringBuilder().append(BinderImpl.VM).append(".getConverter('").append(name).append("')").toString(),
				Converter.class);
			try{
				converter = (Converter)eval.getValue(null, _rootComp, vmc);
			}catch(org.zkoss.zel.MethodNotFoundException x){
				_hasGetConverterMethod = false;
			}
		}
		if(converter == null){
			converter = CONVERTERS.get(name);
		}
		if (converter == null && name.indexOf('.') > 0) { //might be a class path
			try {
				converter = (Converter) Classes.newInstanceByThread(name);
				CONVERTERS.put(name, converter); //assume converter is state-less
			} catch (Exception e) {
				throw UiException.Aide.wrap(e);
			}
		}
		if (converter == null) {
			throw new UiException("Cannot find the named converter:" + name);
		}
		return converter;
	}
	
	//Note: assume system validator is state-less
	public Validator getValidator(String name) {
		checkInit();
		Validator validator = null;
		if(_hasGetValidatorMethod){
			final BindEvaluatorX eval = getEvaluatorX();
			final ExpressionX vmv = eval.parseExpressionX(null, 
				new StringBuilder().append(BinderImpl.VM).append(".getValidator('").append(name).append("')").toString(),
				Validator.class);
			try{
				validator = (Validator)eval.getValue(null, _rootComp, vmv);
			}catch(org.zkoss.zel.MethodNotFoundException x){
				_hasGetValidatorMethod = false;
			}
		}
		if(validator == null){
			validator = VALIDATORS.get(name);
		}
		if (validator == null && name.indexOf('.') > 0) { //might be a class path
			try {
				validator = (Validator) Classes.newInstanceByThread(name);
				VALIDATORS.put(name, validator); //assume converter is state-less
			} catch (Exception e) {
				throw UiException.Aide.wrap(e);
			}
		}
		if (validator == null) {
			throw new UiException("Cannot find the named validator:" + name);
		}
		return validator;
	}
	
	//Note: assume system renderer is state-less 
	protected Object getRenderer(String name) {
		Object renderer = RENDERERS.get(name);
		if (renderer == null && name.indexOf('.') > 0) { //might be a class path
			try {
				renderer = Classes.newInstanceByThread(name);
				RENDERERS.put(name, renderer); //assume renderer is state-less
			} catch (IllegalAccessException e) {
				throw UiException.Aide.wrap(e);
			} catch (Exception e) {
				//ignore
			}
		}
		return renderer;
	}

	public BindEvaluatorX getEvaluatorX() {
		if (_eval == null) {
			_eval = new BindEvaluatorXImpl(null, org.zkoss.bind.xel.BindXelFactory.class);
		}
		return _eval;
	}
	
	public void storeForm(Component comp,String id, Form form){
		final String oldid = (String)comp.getAttribute(FORM_ID, Component.COMPONENT_SCOPE);
		//check if a form exist already, allow to store a form with same id again for replacing the form 
		if(oldid!=null && !oldid.equals(id)){
			throw new IllegalArgumentException("try to store 2 forms in same component id : 1st "+oldid+", 2nd "+id);
		}
		final Form oldForm = (Form)comp.getAttribute(id);
		
		if(oldForm==form) return;
		
		comp.setAttribute(FORM_ID, id);//mark it is a form component with the form id;
		comp.setAttribute(id, form);//after setAttribute, we can access fx in el.
		
		if(form instanceof FormExt){
			final FormExt fex = (FormExt)form;
			comp.setAttribute(id+"Status", fex.getStatus());//by convention fxStatus
			
			if(oldForm instanceof FormExt){//copy the filed information, this is for a form-init that assign a user form
				for(String fn:((FormExt)oldForm).getLoadFieldNames()){
					fex.addLoadFieldName(fn);
				}
				for(String fn:((FormExt)oldForm).getSaveFieldNames()){
					fex.addSaveFieldName(fn);
				}
			}
		}
	}
	
	public Form getForm(Component comp,String id){
		String oldid = (String)comp.getAttribute(FORM_ID, Component.COMPONENT_SCOPE);
		if(oldid==null || !oldid.equals(id)){
			//return null if the id is not correct
			return null;
		}
		return (Form)comp.getAttribute(id);
	}

	private void removeForm(Component comp){
		String id = (String)comp.getAttribute(FORM_ID, Component.COMPONENT_SCOPE);
		if(id!=null){
			comp.removeAttribute(FORM_ID);
			comp.removeAttribute(id);
			comp.removeAttribute(id+"Status");
		}
	}
	
	@Override
	public void addFormInitBinding(Component comp, String id, String initExpr, Map<String, Object> initArgs) {
		checkInit();
		if(Strings.isBlank(id)){
			throw new IllegalArgumentException("form id is blank");
		}
		if(initExpr==null){
			throw new IllegalArgumentException("initExpr is null for component "+comp+", form "+id);
		}
		
		
		Form form = getForm(comp,id);
		if(form==null){
			storeForm(comp,id,new SimpleForm());
		}
		
		addFormInitBinding0(comp,id,initExpr,initArgs);

	}
	
	private void addFormInitBinding0(Component comp, String formId, String initExpr, Map<String, Object> bindingArgs) {
		
		if(_log.debugable()){
			_log.debug("add init-form-binding: comp=[%s],form=[%s],expr=[%s]", comp,formId,initExpr);
		}
		final String attr = formId;
		
		InitFormBindingImpl binding = new InitFormBindingImpl(this, comp, attr, initExpr, bindingArgs);
		
		addBinding(comp, attr, binding);
		final BindingKey bkey = getBindingKey(comp, attr);
		_formBindingHandler.addInitBinding(bkey, binding);
	}
	
	@Override
	public void addFormLoadBindings(Component comp, String id,
			String loadExpr, String[] beforeCmds, String[] afterCmds,
			Map<String, Object> bindingArgs) {
		checkInit();
		if(Strings.isBlank(id)){
			throw new IllegalArgumentException("form id is blank");
		}
		if(loadExpr==null){
			throw new IllegalArgumentException("loadExpr is null for component "+comp+", form "+id);
		}
		
		Form form = getForm(comp,id);
		if(form==null){
			storeForm(comp,id,new SimpleForm());
		}
		
		addFormLoadBindings0(comp,id,loadExpr,beforeCmds,afterCmds,bindingArgs);
	}

	@Override
	public void addFormSaveBindings(Component comp, String id, String saveExpr,
			String[] beforeCmds, String[] afterCmds,
			Map<String, Object> bindingArgs, String validatorExpr,
			Map<String, Object> validatorArgs) {
		checkInit();
		if(Strings.isBlank(id)){
			throw new IllegalArgumentException("form id is blank");
		}
		if(saveExpr==null){
			throw new IllegalArgumentException("saveExpr is null for component "+comp+", form "+id);
		}
		
		Form form = getForm(comp,id);
		if(form==null){
			storeForm(comp,id,new SimpleForm());
		}

		addFormSaveBindings0(comp, id, saveExpr, beforeCmds, afterCmds, bindingArgs, validatorExpr, validatorArgs);
	}

	private void addFormLoadBindings0(Component comp, String formId, String loadExpr, String[] beforeCmds, String[] afterCmds, Map<String, Object> bindingArgs) {
		final boolean prompt = isPrompt(beforeCmds,afterCmds);
		final String attr = formId;
		
		if(prompt){
			final LoadFormBindingImpl binding = new LoadFormBindingImpl(this, comp, formId, loadExpr,ConditionType.PROMPT,null, bindingArgs);
			addBinding(comp, attr, binding);
			final BindingKey bkey = getBindingKey(comp, attr);
			_formBindingHandler.addLoadPromptBinding(bkey, binding);
		}else{
			if(beforeCmds!=null && beforeCmds.length>0){
				for(String cmd:beforeCmds){
					final LoadFormBindingImpl binding = new LoadFormBindingImpl(this, comp, formId, loadExpr,ConditionType.BEFORE_COMMAND,cmd, bindingArgs);
					addBinding(comp, attr, binding);
					if(_log.debugable()){
						_log.debug("add before command-load-form-binding: comp=[%s],attr=[%s],expr=[%s],command=[%s]", comp,attr,loadExpr,cmd);
					}
					_formBindingHandler.addLoadBeforeBinding(cmd, binding);
				}
			}
			if(afterCmds!=null && afterCmds.length>0){
				for(String cmd:afterCmds){
					final LoadFormBindingImpl binding = new LoadFormBindingImpl(this, comp, formId, loadExpr,ConditionType.AFTER_COMMAND,cmd, bindingArgs);
					addBinding(comp, attr, binding);
					if(_log.debugable()){
						_log.debug("add after command-load-form-binding: comp=[%s],attr=[%s],expr=[%s],command=[%s]", comp,attr,loadExpr,cmd);
					}
					_formBindingHandler.addLoadAfterBinding(cmd, binding);
				}
			}
		}
	}

	private void addFormSaveBindings0(Component comp, String formid, String saveExpr, 
			String[] beforeCmds, String[] afterCmds, Map<String, Object> bindingArgs,
			String validatorExpr,Map<String, Object> validatorArgs) {
		final boolean prompt = isPrompt(beforeCmds,afterCmds);
		if(prompt){
			throw new IllegalArgumentException("a save-form-binding have to set with a before|after command condition");
		}
		
		if(beforeCmds!=null && beforeCmds.length>0){
			for(String cmd:beforeCmds){
				final SaveFormBindingImpl binding = new SaveFormBindingImpl(this, comp, formid, saveExpr, ConditionType.BEFORE_COMMAND, cmd, bindingArgs, validatorExpr, validatorArgs);
				addBinding(comp, formid, binding);
				if(_log.debugable()){
					_log.debug("add before command-save-form-binding: comp=[%s],attr=[%s],expr=[%s],command=[%s]", comp,formid,saveExpr,cmd);
				}
				_formBindingHandler.addSaveBeforeBinding(cmd, binding);
			}
		}
		if(afterCmds!=null && afterCmds.length>0){
			for(String cmd:afterCmds){
				final SaveFormBindingImpl binding = new SaveFormBindingImpl(this, comp, formid, saveExpr, ConditionType.AFTER_COMMAND, cmd, bindingArgs, validatorExpr, validatorArgs);
				addBinding(comp, formid, binding);
				if(_log.debugable()){
					_log.debug("add after command-save-form-binding: comp=[%s],attr=[%s],expr=[%s],command=[%s]", comp,formid,saveExpr,cmd);
				}
				_formBindingHandler.addSaveAfterBinding(cmd, binding);
			}
		}
		if(validatorExpr!=null){
			BindingKey bkey = new BindingKey(comp, formid);
			if(!_hasValidators.contains(bkey)){
				_hasValidators.add(bkey);
			}
		}
	}
	
	@Override
	public void addPropertyInitBinding(Component comp, String attr,
			String initExpr,Map<String, Object> initArgs,
			String converterExpr, Map<String, Object> converterArgs) {
		checkInit();
		if(initExpr==null){
			throw new IllegalArgumentException("initExpr is null for "+attr+" of "+comp);
		}
		if (Strings.isBlank(converterExpr)) {
			converterExpr = getSystemConverter(comp, attr);
			if (converterExpr != null) {
				converterExpr = "'"+converterExpr+"'";
			}
		}
		
		addPropertyInitBinding0(comp,attr,initExpr,initArgs,converterExpr,converterArgs);
		
		initRendererIfAny(comp);
	}

	@Override
	public void addPropertyLoadBindings(Component comp, String attr,
			String loadExpr, String[] beforeCmds, String[] afterCmds, Map<String, Object> bindingArgs,
			String converterExpr, Map<String, Object> converterArgs) {
		checkInit();
		if(loadExpr==null){
			throw new IllegalArgumentException("loadExpr is null for component "+comp+", attr "+attr);
		}
		if (Strings.isBlank(converterExpr)) {
			converterExpr = getSystemConverter(comp, attr);
			if (converterExpr != null) {
				converterExpr = "'"+converterExpr+"'";
			}
		}
		
		addPropertyLoadBindings0(comp, attr, loadExpr, beforeCmds, afterCmds, bindingArgs, converterExpr, converterArgs);
		
		initRendererIfAny(comp);
	}

	@Override
	public void addPropertySaveBindings(Component comp, String attr,
			String saveExpr, String[] beforeCmds, String[] afterCmds,
			Map<String, Object> bindingArgs, String converterExpr,
			Map<String, Object> converterArgs, String validatorExpr,
			Map<String, Object> validatorArgs) {
		checkInit();
		if(saveExpr==null){
			throw new IllegalArgumentException("saveExpr is null for component "+comp+", attr "+attr);
		}
		if (Strings.isBlank(converterExpr)) {
			converterExpr = getSystemConverter(comp, attr);
			if (converterExpr != null) {
				converterExpr = "'"+converterExpr+"'";
			}
		}
		if (Strings.isBlank(validatorExpr)) {
			validatorExpr = getSystemValidator(comp, attr);
			if (validatorExpr != null) {
				validatorExpr = "'"+validatorExpr+"'";
			}
		}

		addPropertySaveBindings0(comp, attr, saveExpr, beforeCmds, afterCmds, bindingArgs, 
				converterExpr, converterArgs, validatorExpr, validatorArgs);
	}
	
	
	private void addPropertyInitBinding0(Component comp, String attr,
			String initExpr, Map<String, Object> bindingArgs, String converterExpr, Map<String, Object> converterArgs) {
		
		final ComponentCtrl compCtrl = (ComponentCtrl) comp;
		final Annotation ann = compCtrl.getAnnotation(attr, BinderImpl.SYSBIND);
		String loadrep = null;
		Class<?> attrType = null;//default is any class
		if (ann != null) {
			final Map<String, String[]> attrs = ann.getAttributes(); //(tag, tagExpr)
			loadrep = testString(attrs.get(BinderImpl.LOAD_REPLACEMENT)); //check replacement of attr when loading
			
			final String type = testString(attrs.get(BinderImpl.LOAD_TYPE)); //check type of attr when loading
			if (type != null) {
				try {
					attrType = Classes.forNameByThread(type);
				} catch (ClassNotFoundException e) {
					throw new UiException(e.getMessage(),e);
				}
			}
		}
		loadrep = loadrep == null ? attr : loadrep;
		
		if(_log.debugable()){
			_log.debug("add init-binding: comp=[%s],attr=[%s],expr=[%s],converter=[%s]", comp,attr,initExpr,converterArgs);
		}
		
		InitPropertyBindingImpl binding = new InitPropertyBindingImpl(this, comp, attr, loadrep, attrType, initExpr, bindingArgs, converterExpr, converterArgs);
		
		addBinding(comp, attr, binding); 
		final BindingKey bkey = getBindingKey(comp, attr);
		_propertyBindingHandler.addInitBinding(bkey, binding);
	}

	private String getSystemConverter(Component comp, String attr) {
		final ComponentCtrl compCtrl = (ComponentCtrl) comp;
		final Annotation ann = compCtrl.getAnnotation(attr, BinderImpl.SYSBIND);
		if (ann != null) {
			final Map<String, String[]> attrs = ann.getAttributes(); //(tag, tagExpr)
			return testString(attrs.get(BinderImpl.CONVERTER)); //system converter if exists
		}
		return null;
	}
	
	private String getSystemValidator(Component comp, String attr) {
		final ComponentCtrl compCtrl = (ComponentCtrl) comp;
		final Annotation ann = compCtrl.getAnnotation(attr, BinderImpl.SYSBIND);
		if (ann != null) {
			final Map<String, String[]> attrs = ann.getAttributes(); //(tag, tagExpr)
			return testString(attrs.get(BinderImpl.VALIDATOR)); //system validator if exists
		}
		return null;
	}
	
	private void initRendererIfAny(Component comp) {
		//check if exists template
		final ComponentCtrl compCtrl = (ComponentCtrl) comp;
		final Annotation ann = compCtrl.getAnnotation(null, BinderImpl.SYSBIND);
		final Map<String, String[]> attrs = ann != null ? ann.getAttributes() : null; //(tag, tagExpr)
		final Template tm = comp.getTemplate("model");
		if (tm == null) { //no template
			return;
		}
		
		final Object installed = comp.getAttribute(BinderImpl.VAR);
		if (installed != null) { //renderer was set already init
			return;
		}
		
		final String var = (String) tm.getParameters().get("var");
		final String varnm = var == null ? "each" : var; //var is not specified, default to "each"
		comp.setAttribute(BinderImpl.VAR, varnm);
		
		final String itervar = (String) tm.getParameters().get("status");
		final String itervarnm = itervar == null ? var+"Status" : itervar; //provide default value if not specified
		comp.setAttribute(BinderImpl.ITERATION_VAR, itervarnm);

		if (attrs != null) {
			final String rendererName = testString(attrs.get(BinderImpl.RENDERER)); //renderer if any
			//setup renderer
			if (rendererName != null) { //there was system renderer
				final String[] values = rendererName.split("=", 2);
				if (values != null) {
					final Object renderer = getRenderer(values[1]);
					//check if user has set a renderer
					Object old = null;
					try {
						old = Fields.get(comp, values[0]);
					} catch (NoSuchMethodException e1) {
						//ignore
					}
					if (old == null) {
						try {
							Fields.set(comp, values[0], renderer, false);
						} catch (Exception  e) {
							throw UiException.Aide.wrap(e);
						}
					}
				}
			}
		}
	}
	
	
	private String testString(String[] string){
		if(string==null || string.length==0){
			return null;
		}else if(string.length==1){
			return string[0];
		}else{
			throw new IndexOutOfBoundsException("size="+string.length);
		}
	}
	
	private void addPropertyLoadBindings0(Component comp, String attr,
			String loadExpr, String[] beforeCmds, String[] afterCmds, Map<String, Object> bindingArgs,
			String converterExpr, Map<String, Object> converterArgs) {
		final boolean prompt = isPrompt(beforeCmds,afterCmds);
		
		//check attribute _accessInfo natural characteristics to register Command event listener
		final ComponentCtrl compCtrl = (ComponentCtrl) comp;
		final Annotation ann = compCtrl.getAnnotation(attr, BinderImpl.SYSBIND);
		//check which attribute of component should load to component on which event.
		//the event is usually a engine lifecycle event.
		//ex, listbox's 'selectedIndex' should be loaded to component on 'onAfterRender'
		String evtnm = null;
		String loadrep = null;//loadrep not ready yet
		Class<?> attrType = null;//default is any class
		if (ann != null) {
			final Map<String, String[]> attrs = ann.getAttributes(); //(tag, tagExpr)
			final String rw = (String) testString(attrs.get(BinderImpl.ACCESS)); //_accessInfo right, "both|save|load", default to load
			if (rw != null && !"both".equals(rw) && !"load".equals(rw)) { //save only, skip
				return;
			}
			evtnm = testString(attrs.get(BinderImpl.LOADEVENT)); //check trigger event for loading
			
			loadrep = testString(attrs.get(BinderImpl.LOAD_REPLACEMENT)); //check replacement of attr when loading
			
			final String type = testString(attrs.get(BinderImpl.LOAD_TYPE)); //check type of attr when loading
			if(type!=null){
				try {
					attrType = Classes.forNameByThread(type);
				} catch (ClassNotFoundException e) {
					throw new UiException(e.getMessage(),e);
				}
			}
		}
		loadrep = loadrep == null ? attr : loadrep;
		
		if(prompt){
			if(_log.debugable()){
				_log.debug("add event(prompt)-load-binding: comp=[%s],attr=[%s],expr=[%s],evtnm=[%s],converter=[%s]", comp,attr,loadExpr,evtnm,converterArgs);
			}
			LoadPropertyBindingImpl binding = new LoadPropertyBindingImpl(this, comp, attr, loadrep, attrType, loadExpr, ConditionType.PROMPT, null,  bindingArgs, converterExpr,converterArgs);
			addBinding(comp, attr, binding);
			
			if (evtnm != null) { //special case, load on an event, ex, onAfterRender of listbox on selectedItem
				addEventCommandListenerIfNotExists(comp, evtnm, null); //local command
				final BindingKey bkey = getBindingKey(comp, evtnm);
				_propertyBindingHandler.addLoadEventBinding(comp, bkey, binding);
			}
			//if no command , always add to prompt binding, a prompt binding will be load when , 
			//1.load a component property binding
			//2.property change (TODO, DENNIS, ISSUE, I think loading of property change is triggered by tracker in loadOnPropertyChange, not by prompt-binding 
			final BindingKey bkey = getBindingKey(comp, attr);
			_propertyBindingHandler.addLoadPromptBinding(comp, bkey, binding);
		}else{
			if(beforeCmds!=null && beforeCmds.length>0){
				for(String cmd:beforeCmds){
					LoadPropertyBindingImpl binding = new LoadPropertyBindingImpl(this, comp, attr, loadrep, attrType, loadExpr, ConditionType.BEFORE_COMMAND, cmd, bindingArgs, converterExpr, converterArgs);
					addBinding(comp, attr, binding);
					if(_log.debugable()){
						_log.debug("add before command-load-binding: comp=[%s],att=r[%s],expr=[%s],converter=[%s]", comp,attr,loadExpr,converterExpr);
					}
					_propertyBindingHandler.addLoadBeforeBinding(cmd, binding);
				}
			}
			if(afterCmds!=null && afterCmds.length>0){
				for(String cmd:afterCmds){
					LoadPropertyBindingImpl binding = new LoadPropertyBindingImpl(this, comp, attr, loadrep, attrType, loadExpr,  ConditionType.AFTER_COMMAND, cmd, bindingArgs, converterExpr,converterArgs);
					addBinding(comp, attr, binding);
					if(_log.debugable()){
						_log.debug("add after command-load-binding: comp=[%s],att=r[%s],expr=[%s],converter=[%s]", comp,attr,loadExpr,converterExpr);
					}
					_propertyBindingHandler.addLoadAfterBinding(cmd, binding);	
				}
			}
		}
	}
	
	private void addPropertySaveBindings0(Component comp, String attr, String saveExpr, String[] beforeCmds, String[] afterCmds, Map<String, Object> bindingArgs,
			String converterExpr, Map<String, Object> converterArgs, String validatorExpr, Map<String, Object> validatorArgs) {
		final boolean prompt = isPrompt(beforeCmds,afterCmds);
		//check attribute _accessInfo natural characteristics to register Command event listener 
		final ComponentCtrl compCtrl = (ComponentCtrl) comp;
		final Annotation ann = compCtrl.getAnnotation(attr, BinderImpl.SYSBIND);
		//check which attribute of component should fire save on which event.
		//ex, listbox's 'selectedIndex' should be loaded to component on 'onSelect'
		//ex, checkbox's 'checked' should be saved to bean on 'onCheck'
		String evtnm = null;
		if (ann != null) {
			final Map<String, String[]> attrs = ann.getAttributes(); //(tag, tagExpr)
			final String rw = testString(attrs.get(BinderImpl.ACCESS)); //_accessInfo right, "both|save|load", default to load
			if (!"both".equals(rw) && !"save".equals(rw)) { //load only, skip
				return;
			}
			evtnm = testString(attrs.get(BinderImpl.SAVEEVENT)); //check trigger event for saving
		}
		if (evtnm == null) { 
			//no trigger event, since the value never change of component, so both prompt and command are useless
			return;
		}

		
		if(prompt){
			final SavePropertyBindingImpl binding = new SavePropertyBindingImpl(this, comp, attr, saveExpr, ConditionType.PROMPT, null, bindingArgs, converterExpr, converterArgs, validatorExpr, validatorArgs);
			addBinding(comp, attr, binding);
			if(_log.debugable()){
				_log.debug("add event(prompt)-save-binding: comp=[%s],attr=[%s],expr=[%s],evtnm=[%s],converter=[%s],validate=[%s]", comp,attr,saveExpr,evtnm,converterExpr,validatorExpr);
			}
			addEventCommandListenerIfNotExists(comp, evtnm, null); //local command
			final BindingKey bkey = getBindingKey(comp, evtnm);
			_propertyBindingHandler.addSavePromptBinding(comp, bkey, binding);
		}else{
			if(beforeCmds!=null && beforeCmds.length>0){
				for(String cmd:beforeCmds){
					final SavePropertyBindingImpl binding = new SavePropertyBindingImpl(this, comp, attr, saveExpr, ConditionType.BEFORE_COMMAND, cmd, bindingArgs, converterExpr, converterArgs, validatorExpr, validatorArgs);
					addBinding(comp, attr, binding);
					if(_log.debugable()){
						_log.debug("add before command-save-binding: comp=[%s],att=r[%s],expr=[%s],converter=[%s],validator=[%s]", comp,attr,saveExpr,converterExpr,validatorExpr);
					}
					_propertyBindingHandler.addSaveBeforeBinding(cmd, binding);
				}
			}
			if(afterCmds!=null && afterCmds.length>0){
				for(String cmd:afterCmds){
					final SavePropertyBindingImpl binding = new SavePropertyBindingImpl(this, comp, attr, saveExpr, ConditionType.AFTER_COMMAND, cmd, bindingArgs, converterExpr, converterArgs, validatorExpr, validatorArgs);
					addBinding(comp, attr, binding);
					if(_log.debugable()){
						_log.debug("add after command-save-binding: comp=[%s],att=r[%s],expr=[%s],converter=[%s],validator=[%s]", comp,attr,saveExpr,converterExpr,validatorExpr);
					}
					_propertyBindingHandler.addSaveAfterBinding(cmd, binding);	
				}
			}
		}
		
		if(validatorExpr!=null){
			BindingKey bkey = new BindingKey(comp, attr);
			if(!_hasValidators.contains(bkey)){
				_hasValidators.add(bkey);
			}
		}
	}
	
	private boolean isPrompt(String[] beforeCmds, String[] afterCmds){
		return (beforeCmds==null || beforeCmds.length==0) && (afterCmds==null || afterCmds.length==0);
	}

	public void addCommandBinding(Component comp, String evtnm, String commandExpr, Map<String, Object> args) {
		checkInit();
		final CommandBindingImpl binding = new CommandBindingImpl(this, comp, evtnm, commandExpr, args);
		addBinding(comp, evtnm, binding);
		addEventCommandListenerIfNotExists(comp, evtnm, binding);
	}
	
	//associate event to CommandBinding
	private void addEventCommandListenerIfNotExists(Component comp, String evtnm, CommandBinding command) {
		final BindingKey bkey = getBindingKey(comp, evtnm);
		CommandEventListener listener = _listenerMap.get(bkey);
		if (listener == null) {
			listener = new CommandEventListener(comp);
			comp.addEventListener(evtnm, listener);
			_listenerMap.put(bkey, listener);
		}
		//DENNIS, this method will call by
		//1.addPropertyBinding -> command is null -> means prompt when evtnm is fired.
		//2.addCommandBinding -> command is not null -> means trigger command when evtnm is fired.
		//ex, <textbox value="@bind(vm.firstname)" onChange="@bind('save')"/>
		//and in current spec, we only allow one command to be executed in one event. 
		listener.setCommand(command);
	}
	
	private void removeEventCommandListenerIfExists(Component comp, String evtnm) {
		final BindingKey bkey = getBindingKey(comp, evtnm);
		final CommandEventListener listener = _listenerMap.remove(bkey);
		if (listener != null) {
			comp.removeEventListener(evtnm, listener);
		}
	}

	private class CommandEventListener implements EventListener<Event> { //event used to trigger command
		private boolean _prompt = false;
		private CommandBinding _commandBinding;
		final private Component _target;
		
		CommandEventListener(Component target){
			_target = target;
		}
		
		private void setCommand(CommandBinding command) {
			//if 1.add a non-null command then 2.add a null command, the prompt will be true and commandBinding is not null 
			//ex, <textbox value="@bind(vm.firstname)" onChange="@bind('save')"/>
			if (!_prompt && command == null) {
				_prompt = true;
			} else {
				_commandBinding = command;
			}
		}
		
		public void onEvent(Event event) throws Exception {
			//command need to be confirmed shall be execute first!
			//must sort the command sequence?
			
			//BUG 619, event may come from children of some component, 
			//ex tabbox.onSelect is form tab, so we cannot depend on event's target
			final Component comp = _target;//_target is always equals _commandBinding.getComponent();
			final String evtnm = event.getName();
			final Set<Property> notifys = new LinkedHashSet<Property>();
			int result = SUCCESS; //command execution result, default to success
			String command = null;
			if(_log.debugable()){
				_log.debug("====Start command event [%s]",event);
			}
			if (_commandBinding != null) {
				final BindEvaluatorX eval = getEvaluatorX();
				command = (String) eval.getValue(null, comp, ((CommandBindingImpl)_commandBinding).getCommand());
				final Map<String, Object> args = BindEvaluatorXUtil.evalArgs(eval, comp, _commandBinding.getArgs());
				result = BinderImpl.this.doCommand(comp, command, event, args, notifys/*, false*/);
			}
			//check confirm
			switch(result) {
//				case BinderImpl.FAIL_CONFIRM:
//					BinderImpl.this.doFailConfirm();
//					break;
				case BinderImpl.FAIL_VALIDATE:
					//validationmessages, the last notified
					if(_validationMessages!=null){
						notifys.add(new PropertyImpl(_validationMessages,".",null));
					}
					
					if(_log.debugable()){
						_log.debug("There are [%s] property need to be notify after fail validate",notifys.size());
					}
					fireNotifyChanges(notifys); //still has to go through notifyChange to show error message
					return;
			}
			//confirm might cancel the operation, on event binding must be the last one to be done 
			if (_prompt) {
				if(_log.debugable()){
					_log.debug("This is a prompt command");
				}
				if (command != null) { //command has own VALIDATE phase, don't do validate again
					BinderImpl.this.doSaveEventNoValidate(comp, event, notifys); //save on event without validation
				} else {
					BinderImpl.this.doSaveEvent(comp, event, notifys); //save on event
				}
				BinderImpl.this.doLoadEvent(comp, evtnm); //load on event
			}

			if(_validationMessages!=null){
				//validationmessages the last notified
				notifys.add(new PropertyImpl(_validationMessages,".",null));
			}
			
			if(_log.debugable()){
				_log.debug("There are [%s] property need to be notify after command",notifys.size());
			}
			fireNotifyChanges(notifys);
			if(_log.debugable()){
				_log.debug("====End command event [%s]",event);
			}
		}
	}
	
	public void sendCommand(String command, Map<String, Object> args) {
		checkInit();
		final Set<Property> notifys = new HashSet<Property>();
		//args come from user, we don't eval it. 
		doCommand(_rootComp, command, null, args, notifys);
		fireNotifyChanges(notifys);
	}

	private void fireNotifyChanges(Set<Property> notifys) {
		for(Property prop : notifys) {
			notifyChange(prop.getBase(), prop.getProperty());
		}
	}
	
	public void postCommand(String command, Map<String, Object> args) {
		checkInit();
		final Event evt = new Event(ON_POST_COMMAND,_dummyTarget,new Object[]{command,args});
		Events.postEvent(evt);
	}
	
	//comp the component that trigger the command
	//major life cycle of binding (on event trigger)
	//command is the command name after evaluation
	//evt event that fire this command
	//args the passed in argument for executing command
	//notifies container for properties that is to be notifyChange
	//skipConfirm whether skip checking confirm 
	//return properties to be notified change
	private int doCommand(Component comp, String command, Event evt, Map<String, Object> commandArgs, Set<Property> notifys/*, boolean skipConfirm*/) {
		final String evtnm = evt == null ? null : evt.getName();
		if(_log.debugable()){
			_log.debug("Start doCommand comp=[%s],command=[%s],evtnm=[%s]",comp,command,evtnm);
		}
		BindContext ctx = BindContextUtil.newBindContext(this, null, false, command, comp, evt);
		BindContextUtil.setCommandArgs(this, comp, ctx, commandArgs);
		try {
			doPrePhase(Phase.COMMAND, ctx); //begin of Command
			boolean success = true;
			
			//validate
			success = doValidate(comp, command, evt, ctx, notifys);
			if (!success) {
				return FAIL_VALIDATE;
			}
			
			//save before command bindings
			doSaveBefore(comp, command, evt, ctx, notifys);
			
			//load before command bindings
			doLoadBefore(comp, command, ctx);
			
			//execute command
			doExecute(comp, command, commandArgs, ctx, notifys);
			
			//save after command bindings
			doSaveAfter(comp, command, evt, ctx, notifys);
			
			//load after command bindings
			doLoadAfter(comp, command, ctx);
			if(_log.debugable()){
				_log.debug("End doCommand");
			}
			return SUCCESS;
		} finally {
			doPostPhase(Phase.COMMAND, ctx); //end of Command
		}
		
	}
	
	/*package*/ void doPrePhase(Phase phase, BindContext ctx) {
		if (_phaseListener != null) {
			_phaseListener.prePhase(phase, ctx);
		}
	}
	
	/*package*/ void doPostPhase(Phase phase, BindContext ctx) {
		if (_phaseListener != null) {
			_phaseListener.postPhase(phase, ctx);
		}
	}
	//for event -> prompt only, no command 
	private void doSaveEventNoValidate(Component comp, Event evt, Set<Property> notifys) {
		final String evtnm = evt == null ? null : evt.getName();
		if(_log.debugable()){
			_log.debug("doSaveEventNoValidate comp=[%s],evtnm=[%s],notifys=[%s]",comp,evtnm,notifys);
		}
		final BindingKey bkey = getBindingKey(comp, evtnm);
		_propertyBindingHandler.doSaveEventNoValidate(bkey, comp, evt, notifys);
	}
	//for event -> prompt only, no command 
	private boolean doSaveEvent(Component comp, Event evt, Set<Property> notifys) {
		final String evtnm = evt == null ? null : evt.getName();
		if(_log.debugable()){
			_log.debug("doSaveEvent comp=[%s],evtnm=[%s],notifys=[%s]",comp,evtnm,notifys);
		}
		final BindingKey bkey = getBindingKey(comp, evtnm);
		return _propertyBindingHandler.doSaveEvent(bkey, comp, evt, notifys);
	}
	
	//for event -> prompt only, no command
	private void doLoadEvent(Component comp, String evtnm) {
		if(_log.debugable()){
			_log.debug("doLoadEvent comp=[%s],evtnm=[%s]",comp,evtnm);
		}
		final BindingKey bkey = getBindingKey(comp, evtnm); 
		_propertyBindingHandler.doLoadEvent(bkey, comp, evtnm);
	}
	
	//doCommand -> doValidate
	private boolean doValidate(Component comp, String command, Event evt, BindContext ctx, Set<Property> notifys) {
		final Set<Property> validates = new HashSet<Property>();
		try {
			if(_log.debugable()){
				_log.debug("doValidate comp=[%s],command=[%s],evt=[%s],context=[%s]",comp,command,evt,ctx);
			}
			doPrePhase(Phase.VALIDATE, ctx);
			
			//we collect properties that need to be validated, than validate one-by-one
			ValidationHelper vHelper = new ValidationHelper(this,new ValidationHelper.InfoProvider() {
				public Map<String, List<SaveFormBinding>> getSaveFormBeforeBindings() {
					return _formBindingHandler.getSaveFormBeforeBindings();
				}		
				public Map<String, List<SaveFormBinding>> getSaveFormAfterBindings() {
					return _formBindingHandler.getSaveFormAfterBindings();
				}
				public Map<BindingKey, List<SavePropertyBinding>> getSaveEventBindings() {
					return _propertyBindingHandler.getSaveEventBindings();
				}
				public Map<String, List<SavePropertyBinding>> getSaveBeforeBindings() {
					return _propertyBindingHandler.getSaveBeforeBindings();
				}
				public Map<String, List<SavePropertyBinding>> getSaveAfterBindings() {
					return _propertyBindingHandler.getSaveAfterBindings();
				}
				public BindingKey getBindingKey(Component comp, String attr) {
					return BinderImpl.this.getBindingKey(comp,attr);
				}
			});
			
			//collect Property of special command for validation in validates
			vHelper.collectSaveBefore(comp, command, evt, validates);
			vHelper.collectSaveAfter(comp, command, evt, validates);
			if (evt != null) {
				//also collect the validate on the prompt save-bind that is related to evt 
				vHelper.collectSaveEvent(comp, command, evt, validates);
			}
			
			//do validation (defined by application)
			if (validates.isEmpty()) {
				return true;
			} else {
				if(_log.debugable()){
					_log.debug("doValidate validates=[%s]",validates);
				}
				boolean valid = true;
				Map<String,Property[]> properties = _propertyBindingHandler.toCollectedProperties(validates);
				valid &= vHelper.validateSaveBefore(comp, command, properties,valid,notifys);
				valid &= vHelper.validateSaveAfter(comp, command, properties,valid,notifys);
				if (evt != null) {
					//also collect the validate on the prompt save-bind that is related to evt 
					valid &= vHelper.validateSaveEvent(comp, command, evt, properties,valid,notifys);
				}
				return valid;
			}
		} catch (Exception e) {
			throw UiException.Aide.wrap(e);
		} finally {
			doPostPhase(Phase.VALIDATE, ctx);
		}
	}
	
	private ParamCall createParamCall(BindContext ctx){
		final ParamCall call = new ParamCall();
		call.setBinder(this);
		call.setBindContext(ctx);
		final Component comp = ctx.getComponent();
		if(comp!=null){
			call.setComponent(comp);
		}
		final Execution exec = Executions.getCurrent();
		if(exec!=null){
			call.setExecution(exec);
		}
		
		return call;
	}
	
	
	private void doExecute(Component comp, String command, Map<String, Object> commandArgs, BindContext ctx, Set<Property> notifys) {
		try {
			if(_log.debugable()){
				_log.debug("before doExecute comp=[%s],command=[%s],notifys=[%s]",comp,command,notifys);
			}
			doPrePhase(Phase.EXECUTE, ctx);
			
			final Object viewModel = getViewModel();
			
			Method method = getCommandMethod(viewModel.getClass(), command);
			if (method != null) {
				
				ParamCall parCall = createParamCall(ctx);
				if(commandArgs != null){
					parCall.setBindingArgs(commandArgs);
				}
				
				parCall.call(viewModel, method);
				
				notifys.addAll(BindELContext.getNotifys(method, viewModel,
						(String) null, (Object) null)); // collect notifyChange
			}else{
				throw new UiException("cannot find any method that is annotated for the command "+command+" with @Command in "+viewModel);
			}
			if(_log.debugable()){
				_log.debug("after doExecute notifys=[%s]", notifys);
			}
		} finally {
			doPostPhase(Phase.EXECUTE, ctx);
		}
	}

	
	private Method getCommandMethod(Class<?> clz, String command) {
		Map<String,Box<Method>> methods = _commandMethodCache.get(clz);
		if(methods==null){
			synchronized(_commandMethodCache){
				methods = _commandMethodCache.get(clz);//check again
				if(methods==null){
					methods = new HashMap<String,Box<Method>>();
					_commandMethodCache.put(clz, methods);
				}
			}
		}
		
		Box<Method> method = methods.get(command);
		if(method!=null){
			return method.value;
		}
		synchronized(methods){
			method = methods.get(command);//check again
			if(method!=null){
				return method.value;
			}
			//scan
			for(Method m : clz.getMethods()){
				final Command cmd = m.getAnnotation(Command.class);
				if(cmd==null) continue;			
				String[] vals = cmd.value();
				if(vals.length==0){
					vals = new String[]{m.getName()};//default method name
				}
				for(String val:vals){
					if(!command.equals(val)) continue;
					if(method!=null){
						throw new UiException("there are more than one method listen to command "+command);
					}
					method = new Box<Method>(m);
					//don't break, for testing duplicate command method
				}
				//don't break, for testing duplicate command method
			}
			if(method==null){//mark not found
				method = new Box<Method>(null);
			}
			//cache it
			methods.put(command, method);
		}
		return method.value;
	}

	//doCommand -> doSaveBefore
	private void doSaveBefore(Component comp, String command, Event evt,  BindContext ctx, Set<Property> notifys) {
		if(_log.debugable()){
			_log.debug("doSaveBefore, comp=[%s],command=[%s],evt=[%s],notifys=[%s]",comp,command,evt,notifys);
		}
		try {
			doPrePhase(Phase.SAVE_BEFORE, ctx);		
			_propertyBindingHandler.doSaveBefore(comp, command, evt, notifys);
			_formBindingHandler.doSaveBefore(comp, command, evt, notifys);
		} finally {
			doPostPhase(Phase.SAVE_BEFORE, ctx);
		}
	}

	
	private void doSaveAfter(Component comp, String command, Event evt, BindContext ctx, Set<Property> notifys) {
		if(_log.debugable()){
			_log.debug("doSaveAfter, comp=[%s],command=[%s],evt=[%s],notifys=[%s]",comp,command,evt,notifys);
		}
		try {
			doPrePhase(Phase.SAVE_AFTER, ctx);
			_propertyBindingHandler.doSaveAfter(comp, command, evt, notifys);
			_formBindingHandler.doSaveAfter(comp, command, evt, notifys);
		} finally {
			doPostPhase(Phase.SAVE_AFTER, ctx);
		}		
		
	}

	
	private void doLoadBefore(Component comp, String command, BindContext ctx) {
		if(_log.debugable()){
			_log.debug("doLoadBefore, comp=[%s],command=[%s]",comp,command);
		}
		try {
			doPrePhase(Phase.LOAD_BEFORE, ctx);		
			_propertyBindingHandler.doLoadBefore(comp, command);
			_formBindingHandler.doLoadBefore(comp, command);
		} finally {
			doPostPhase(Phase.LOAD_BEFORE, ctx);
		}
	}
	
	private void doLoadAfter(Component comp, String command, BindContext ctx) {
		if(_log.debugable()){
			_log.debug("doLoadAfter, comp=[%s],command=[%s]",comp,command);
		}
		try {
			doPrePhase(Phase.LOAD_AFTER, ctx);
			_propertyBindingHandler.doLoadAfter(comp, command);
			_formBindingHandler.doLoadAfter(comp, command);
		} finally {
			doPostPhase(Phase.LOAD_AFTER, ctx);
		}		

	}
	
	/**
	 * Remove all bindings that associated with the specified component.
	 * @param comp the component
	 */
	public void removeBindings(Component comp) {
		checkInit();
		if(_rootComp==comp){
			//the binder component was detached, unregister queue
			unsubscribeChangeListener(_quename, _quescope, _queueListener);
		}
		if(_validationMessages!=null){
			_validationMessages.clearMessages(comp);
		}
		
		final Map<String, List<Binding>> attrMap = _bindings.remove(comp);
		if (attrMap != null) {
			final Set<Binding> removed = new HashSet<Binding>();
			for(Entry<String, List<Binding>> entry : attrMap.entrySet()) {
				final String key = entry.getKey(); 
//				removeEventCommandListenerIfExists(comp, key); //_listenerMap; //comp+evtnm -> eventlistener
				removeBindings(comp, key);
				removed.addAll(entry.getValue());
			}
			if (!removed.isEmpty()) {
				removeBindings(removed);
			}
		}
		
		removeFormAssociatedSaveBinding(comp);
		removeForm(comp);
		
		//remove trackings
		TrackerImpl tracker = (TrackerImpl) getTracker();
		tracker.removeTrackings(comp);

		comp.removeAttribute(BINDER);
	}

	/**
	 * Remove all bindings that associated with the specified component and key (_fieldExpr|evtnm|formid).
	 * @param comp the component
	 * @param key can be component attribute, event name, or form id
	 */
	public void removeBindings(Component comp, String key) {
		checkInit();
		removeEventCommandListenerIfExists(comp, key); //_listenerMap; //comp+evtnm -> eventlistener
		
		final BindingKey bkey = getBindingKey(comp, key);
		final Set<Binding> removed = new HashSet<Binding>();
		
		_formBindingHandler.removeBindings(bkey,removed);
		_propertyBindingHandler.removeBindings(bkey, removed);
		if(_validationMessages!=null){
			_validationMessages.clearMessages(comp,key);
		}
		_hasValidators.remove(bkey);
		
		removeBindings(removed);
	}

	private void removeBindings(Collection<Binding> removed) {
		_formBindingHandler.removeBindings(removed);
		_propertyBindingHandler.removeBindings(removed);
	}
	
	private void addBinding(Component comp, String attr, Binding binding) {
		Map<String, List<Binding>> attrMap = _bindings.get(comp);
		if (attrMap == null) {
			//bug 657, we have to keep the attribute assignment order.
			attrMap = new LinkedHashMap<String, List<Binding>>(); 
			_bindings.put(comp, attrMap);
		}
		List<Binding> bindings = attrMap.get(attr);
		if (bindings == null) {
			bindings = new ArrayList<Binding>();
			attrMap.put(attr, bindings);
		}
		bindings.add(binding);
		
		//associate component with this binder, which means, one component can only bind by one binder
		comp.setAttribute(BINDER, this);
	}

	public Tracker getTracker() {
		if (_tracker == null) {
			_tracker = new TrackerImpl();
		}
		return _tracker;
	}
	
	/**
	 * Internal Use only. init and load the component
	 */
	public void loadComponent(Component comp,boolean loadinit) {
		loadComponentProperties(comp,loadinit);
		for(Component kid = comp.getFirstChild(); kid != null; kid = kid.getNextSibling()) {
			loadComponent(kid,loadinit); //recursive
		}
	}
	
	private void loadComponentProperties(Component comp,boolean loadinit) {
		
		final Map<String, List<Binding>> compBindings = _bindings.get(comp);
		if (compBindings != null) {
			for(String key : compBindings.keySet()) {
				final BindingKey bkey = getBindingKey(comp, key);
				_formBindingHandler.initComponentProperties(comp,bkey);
				_formBindingHandler.loadComponentProperties(comp,bkey);
			}
			for(String key : compBindings.keySet()) {
				final BindingKey bkey = getBindingKey(comp, key);
				_propertyBindingHandler.initComponentProperties(comp,bkey);
				_propertyBindingHandler.loadComponentProperties(comp,bkey);
			}
		}
	}
	
	public void notifyChange(Object base, String attr) {
		checkInit();
		if(_log.debugable()){
			_log.debug("notifyChange base=[%s],attr=[%s]",base,attr);
		}
		getEventQueue().publish(new PropertyChangeEvent("onPropertyChange", _rootComp, base, attr));
	}
	
	public void setPhaseListener(PhaseListener listener) {
		_phaseListener = listener;
	}
	
	PhaseListener getPhaseListener(){
		return _phaseListener;
	}

	private void subscribeChangeListener(String quename, String quescope, EventListener<Event> listener) {
		EventQueue<Event> que = EventQueues.lookup(quename, quescope, true);
		que.subscribe(listener);
	}
	
	private void unsubscribeChangeListener(String quename, String quescope, EventListener<Event> listener) {
		EventQueue<Event> que = EventQueues.lookup(quename, quescope, false);
		if(que!=null){
			que.unsubscribe(listener);
		}
	}
	
	private class PropertyChangeEvent extends Event {
		private static final long serialVersionUID = 201109091736L;
		private final Object _base;
		private final String _propName;

		public PropertyChangeEvent(String name, Component comp, Object base, String propName) {
			super(name, comp);
			this._base = base;
			this._propName = propName;
		}

		public Object getBase() {
			return _base;
		}

		public String getPropertyName() {
			return _propName;
		}
	}
	
	protected EventQueue<Event> getEventQueue() {
		return EventQueues.lookup(_quename, _quescope, true);
	}

	// create a unique id base on component's uuid and attr
	private BindingKey getBindingKey(Component comp, String attr) {
		return new BindingKey(comp,attr);
	}

	private class PostCommandListener implements EventListener<Event>{
		@SuppressWarnings("unchecked")
		public void onEvent(Event event) throws Exception {
			Object[] data = (Object[])event.getData();
			String command = (String)data[0];
			Map<String,Object> args = (Map<String,Object>)data[1]; 
			sendCommand(command, args);
		}
	}
	

	private void removeFormAssociatedSaveBinding(Component comp) {
		_assocFormSaveBindings.remove(comp);
		Map<SaveBinding,Set<SaveBinding>> associated = _reversedAssocFormSaveBindings.remove(comp);
		if(associated!=null){
			Set<Entry<SaveBinding,Set<SaveBinding>>> entries = associated.entrySet();
			for(Entry<SaveBinding,Set<SaveBinding>> entry:entries){
				entry.getValue().remove(entry.getKey());
			}
		}
	}
	
	@Override
	public void addFormAssociatedSaveBinding(Component associatedComp, String formId, SaveBinding saveBinding){
		checkInit();
		//find the form component by form id and a associated/nested component
		Component formComp = lookupAossicatedFormComponent(formId,associatedComp);
		if(formComp==null){
			throw new UiException("cannot find any form "+formId+" with "+associatedComp);
		}
		Set<SaveBinding> bindings = _assocFormSaveBindings.get(formComp);
		if(bindings==null){
			bindings = new LinkedHashSet<SaveBinding>();//keep the order
			_assocFormSaveBindings.put(formComp, bindings);
		}
		bindings.add(saveBinding);
		
		//keep the reverse association , so we can remove it if the associated component is detached (and the form component is not).
		Map<SaveBinding,Set<SaveBinding>> reverseMap = _reversedAssocFormSaveBindings.get(associatedComp);
		if(reverseMap==null){
			reverseMap = new HashMap<SaveBinding, Set<SaveBinding>>();
			_reversedAssocFormSaveBindings.put(associatedComp, reverseMap);
		}
		reverseMap.put(saveBinding,bindings);
	}
	
	private Component lookupAossicatedFormComponent(String formId,Component associatedComp) {
		String fid = null;
		Component p = associatedComp;
		while(p!=null){
			fid = (String)p.getAttribute(FORM_ID);//check in default component scope
			if(fid!=null && fid.equals(formId)){
				break;
			}
			p = p.getParent();
		}
		
		return p;
	}

	@Override
	public Set<SaveBinding> getFormAssociatedSaveBindings(Component comp){
		checkInit();
		Set<SaveBinding> bindings = _assocFormSaveBindings.get(comp);
		if(bindings==null){
			return Collections.emptySet();
		}
		return new LinkedHashSet<SaveBinding>(bindings);//keep the order
	}

	//utility to simply hold a value which might be null
	private static class Box<T> {
		final T value;
		public Box(T value){
			this.value = value;
		}
	}
	
	public boolean hasValidator(Component comp, String attr){
		BindingKey bkey = new BindingKey(comp, attr);
		return _hasValidators.contains(bkey);
	}

	@Override
	public Component getView() {
		checkInit();
		return _rootComp;
	}

	@Override
	public ValidationMessages getValidationMessages() {
		return _validationMessages;
	}

	@Override
	public void setValidationMessages(ValidationMessages messages) {
		_validationMessages = messages;
	}
	
	
}