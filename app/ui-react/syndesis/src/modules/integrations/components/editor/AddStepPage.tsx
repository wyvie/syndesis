import { getSteps } from '@syndesis/api';
import { Step } from '@syndesis/models';
import { Container, IntegrationEditorLayout } from '@syndesis/ui';
import { WithRouteData } from '@syndesis/utils';
import * as H from 'history';
import * as React from 'react';
import { PageTitle } from '../../../../shared';
import { IntegrationEditorStepAdder } from '../IntegrationEditorStepAdder';
import { IBaseRouteParams, IBaseRouteState } from './interfaces';

export interface IAddStepPageProps {
  cancelHref: (p: IBaseRouteParams, s: IBaseRouteState) => H.LocationDescriptor;
  getEditAddStepHref: (
    position: number,
    p: IBaseRouteParams,
    s: IBaseRouteState
  ) => H.LocationDescriptor;
  getEditConfigureStepHrefCallback: (
    stepIdx: number,
    step: Step,
    p: IBaseRouteParams,
    s: IBaseRouteState
  ) => H.LocationDescriptor;
  header: React.ReactNode;
  nextHref: (p: IBaseRouteParams, s: IBaseRouteState) => H.LocationDescriptor;
}

/**
 * This page shows the steps of an existing integration.
 *
 * This component expects a [state]{@link IBaseRouteState} to be properly set in
 * the route object.
 *
 * **Warning:** this component will throw an exception if the route state is
 * undefined.
 *
 * @todo make this page shareable by making the [integration]{@link IBaseRouteState#integration}
 * optinal and adding a WithIntegration component to retrieve the integration
 * from the backend
 */
export class AddStepPage extends React.Component<IAddStepPageProps> {
  public render() {
    return (
      <WithRouteData<IBaseRouteParams, IBaseRouteState>>
        {({ flow }, { integration }) => (
          <IntegrationEditorLayout
            header={this.props.header}
            content={
              <>
                <PageTitle title={'Save or add step'} />
                <Container>
                  <h1>Add to Integration</h1>
                  <p>
                    You can continue adding steps and connections to your
                    integration as well.
                  </p>
                  <IntegrationEditorStepAdder
                    steps={getSteps(integration, 0)}
                    addStepHref={position =>
                      this.props.getEditAddStepHref(
                        position,
                        { flow },
                        { integration }
                      )
                    }
                    configureStepHref={(stepIdx: number, step: Step) =>
                      this.props.getEditConfigureStepHrefCallback(
                        stepIdx,
                        step,
                        { flow },
                        { integration }
                      )
                    }
                  />
                </Container>
              </>
            }
            cancelHref={this.props.cancelHref({ flow }, { integration })}
            nextHref={this.props.nextHref({ flow }, { integration })}
          />
        )}
      </WithRouteData>
    );
  }
}
