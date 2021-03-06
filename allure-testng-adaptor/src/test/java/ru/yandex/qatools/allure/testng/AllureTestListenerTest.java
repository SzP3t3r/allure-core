package ru.yandex.qatools.allure.testng;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.testng.ISuite;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;
import org.testng.internal.ConstructorOrMethod;
import org.testng.xml.XmlTest;
import ru.yandex.qatools.allure.Allure;
import ru.yandex.qatools.allure.annotations.Parameter;
import ru.yandex.qatools.allure.events.AddParameterEvent;
import ru.yandex.qatools.allure.events.TestCaseCanceledEvent;
import ru.yandex.qatools.allure.events.TestCaseFinishedEvent;
import ru.yandex.qatools.allure.events.TestCaseStartedEvent;
import ru.yandex.qatools.allure.model.ParameterKind;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.*;
import static ru.yandex.qatools.allure.utils.AnnotationManager.withExecutorInfo;

/**
 * @author Kirill Kozlov kozlov.k.e@gmail.com
 *         Date: 02.02.14
 */
public class AllureTestListenerTest {

    private static final String DEFAULT_TEST_NAME = "test";
    private static final String DEFAULT_SUITE_NAME = "suite";
    private static final String DEFAULT_XML_TEST_NAME = "testxml";
    
    private AllureTestListener testngListener;
    private Allure allure;
    private ITestContext testContext;
    private ITestResult testResult;
    private ITestNGMethod method;

    @Before
    public void setUp() {
        testngListener = spy(new AllureTestListener());
        allure = mock(Allure.class);

        testngListener.setLifecycle(allure);
        
        ISuite suite = mock(ISuite.class);
    	when(suite.getName()).thenReturn(DEFAULT_SUITE_NAME);
    	XmlTest xmlTest = mock(XmlTest.class);
    	when(xmlTest.getName()).thenReturn(DEFAULT_XML_TEST_NAME);
    	testContext = mock(ITestContext.class);
    	when(testContext.getSuite()).thenReturn(suite);
    	when(testContext.getCurrentXmlTest()).thenReturn(xmlTest);

        // mocking test method parameters
        ConstructorOrMethod constructorOrMethod = mock(ConstructorOrMethod.class);
        when(constructorOrMethod.getMethod()).thenReturn(parametrizedTestMethod(0, null, null));
        method = mock(ITestNGMethod.class);
        when(method.getConstructorOrMethod()).thenReturn(constructorOrMethod);
        testResult = mock(ITestResult.class);
        when(testResult.getMethod()).thenReturn(method);
        when(testResult.getParameters()).thenReturn(new Object[]{});
    }

    @Test
    public void skipTestFireTestCaseStartedEvent() {
        when(testResult.getName()).thenReturn(DEFAULT_TEST_NAME);
        when(testResult.getTestContext()).thenReturn(testContext);
        doReturn(new Annotation[0]).when(testngListener).getMethodAnnotations(testResult);

        testngListener.onTestSkipped(testResult);

        String suiteUid = testngListener.getSuiteUid(testContext);
        verify(allure).fire(eq(withExecutorInfo(new TestCaseStartedEvent(suiteUid, DEFAULT_TEST_NAME))));
    }

    @Test
    public void skipTestWithThrowable() {
        Throwable throwable = new NullPointerException();
        when(testResult.getTestContext()).thenReturn(testContext);
        when(testResult.getThrowable()).thenReturn(throwable);
        when(testResult.getName()).thenReturn(DEFAULT_TEST_NAME);

        doReturn(new Annotation[0]).when(testngListener).getMethodAnnotations(testResult);

        testngListener.onTestSkipped(testResult);

        verify(allure).fire(eq(new TestCaseCanceledEvent().withThrowable(throwable)));
    }

    @Test
    public void skipTestWithoutThrowable() {
        when(testResult.getTestContext()).thenReturn(testContext);
        when(testResult.getName()).thenReturn(DEFAULT_TEST_NAME);

        doReturn(new Annotation[0]).when(testngListener).getMethodAnnotations(testResult);

        testngListener.onTestSkipped(testResult);

        verify(allure).fire(isA(TestCaseCanceledEvent.class));
    }

    @Test
    public void skipTestFiredEventsOrder() {
        when(testResult.getTestContext()).thenReturn(testContext);
        when(testResult.getThrowable()).thenReturn(new NullPointerException());
        when(testResult.getName()).thenReturn(DEFAULT_TEST_NAME);

        doReturn(new Annotation[0]).when(testngListener).getMethodAnnotations(testResult);

        testngListener.onTestSkipped(testResult);

        InOrder inOrder = inOrder(allure);
        inOrder.verify(allure).fire(isA(TestCaseStartedEvent.class));
        inOrder.verify(allure).fire(isA(TestCaseCanceledEvent.class));
        inOrder.verify(allure).fire(isA(TestCaseFinishedEvent.class));
    }

    @Test
    public void parametrizedTest() {
        double doubleParameter = 10.0;
        String stringParameter = "string";
        String anotherStringParameter = "anotherString";
        when(testResult.getTestContext()).thenReturn(testContext);
        when(testResult.getName()).thenReturn(DEFAULT_TEST_NAME);
        when(testResult.getParameters()).thenReturn(new Object[] { doubleParameter, stringParameter, anotherStringParameter});

        doReturn(new Annotation[0]).when(testngListener).getMethodAnnotations(testResult);

        testngListener.onTestStart(testResult);

        String suiteUid = testngListener.getSuiteUid(testContext);
        String testName = String.format("%s[%s,%s,%s]",
                DEFAULT_TEST_NAME, Double.toString(doubleParameter), stringParameter, anotherStringParameter);
        verify(allure).fire(eq(withExecutorInfo(new TestCaseStartedEvent(suiteUid, testName))));

        ArgumentCaptor<AddParameterEvent> captor = ArgumentCaptor.forClass(AddParameterEvent.class);
        verify(allure, times(2)).fire(captor.capture());

        Iterator<AddParameterEvent> addParameterEvents = captor.getAllValues().iterator();
        assertParameterEvent("doubleParameter", doubleParameter + "", addParameterEvents.next(), false);
        assertParameterEvent("valueFromAnnotation", stringParameter, addParameterEvents.next(), true);
        assertFalse(addParameterEvents.hasNext());
    }

    private void assertParameterEvent(@SuppressWarnings("UnusedParameters") String expectedName, String expectedValue, AddParameterEvent event, boolean annotatedParameter) {

        // argument name is available only since Java 8 if compiled javac with -parameters key
        // or if explicitly marked with @ru.yandex.qatools.allure.annotations.Parameter annotation
        if (annotatedParameter) {
            assertEquals(expectedName, event.getName());
        }

        assertEquals(expectedValue, event.getValue());
        assertEquals(ParameterKind.ARGUMENT.name(), event.getKind());
    }

    @SuppressWarnings("UnusedParameters")
    public Method parametrizedTestMethod(@Parameter double doubleParameter, @Parameter("valueFromAnnotation") String stringParameter, String notParameter) {
        try {
            return getClass().getDeclaredMethod("parametrizedTestMethod", double.class, String.class, String.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void currentSuiteTitleTest() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("key", "value");
        XmlTest xmlTest = mock(XmlTest.class);
        when(xmlTest.getLocalParameters()).thenReturn(params);
        when(xmlTest.getName()).thenReturn("xmlName");

        ITestContext iTestContext = mock(ITestContext.class);
        when(iTestContext.getCurrentXmlTest()).thenReturn(xmlTest);

        ISuite iSuite = mock(ISuite.class);
        when(iSuite.getName()).thenReturn("name");
        when(iTestContext.getSuite()).thenReturn(iSuite);

        String name = testngListener.getCurrentSuiteTitle(iTestContext);
        assertThat(name, is("name : xmlName[key=value]"));
    }
}
